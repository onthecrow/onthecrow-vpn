package com.onthecrow.deltavpn.xray.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.onthecrow.deltavpn.errorreporting.ErrorDomain
import com.onthecrow.deltavpn.errorreporting.ErrorReporter
import com.onthecrow.deltavpn.xray.AndroidXrayEnvironment
import com.onthecrow.deltavpn.xray.OtcLog
import com.onthecrow.deltavpn.xray.XrayEngine
import com.onthecrow.deltavpn.xray.XrayRunResult
import com.onthecrow.deltavpn.xray.XrayValidationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val LOG_TAG = "XRAYPROXY"

/**
 * [XrayEngine] as seen from the app process: every call is a binder transaction into `:xray`.
 *
 * Exists so that libXray is never class-loaded here. That matters more after the split than it did
 * before: this process now owns the tun and is meant to outlive every engine restart, so anything Go
 * leaves behind in it — the package-level globals that made a fresh process necessary in the first
 * place — would accumulate for the whole session with no way to clear it.
 *
 * The binding is lazy and long-lived. Nothing binds until something actually asks the engine for
 * something, so a user who never connects never starts the process.
 */
/**
 * One per process, deliberately.
 *
 * There are two callers — the config screens, which only validate, and the VpnService, which runs the
 * tunnel — and giving them an instance each meant two independent bindings to the same service. That
 * is not merely wasteful: `BIND_AUTO_CREATE` makes a binding a standing request for the service to
 * exist, so the second one would resurrect `:xray` the moment the first killed it, and the engine
 * process would then never go away at all — including after a disconnect, whose whole point is to take
 * xray's global connection pool with it.
 */
internal object XrayEngineHolder {
    val engine = RemoteXrayEngine()
}

internal class RemoteXrayEngine(
) : XrayEngine, KoinComponent {

    // Caught IPC non-fatals → Crashlytics (message scrubbed). This proxy lives in the MAIN process, so
    // Firebase is available; the `:xray` side (XrayEngineService) must never report.
    private val errorReporter: ErrorReporter by inject()

    /**
     * Sent to `:xray` on every call so it can bounce `protect()` back here if it turns out it cannot do
     * it itself. Installed by the tunnel owner rather than passed at construction, because the same
     * engine instance also serves callers that merely validate — and validation parses text and dials
     * nothing, so before a tunnel exists there is nothing to protect and nobody able to.
     */
    @Volatile
    private var hostBinder: IBinder? = null

    /**
     * Called when the engine process dies WITHOUT us asking — a crash, or the low-memory killer.
     * Delivered on a binder thread, so it must hand the work elsewhere rather than do it there. A death
     * we requested via [killEngineProcess] does not fire it.
     */
    @Volatile
    private var onUnexpectedDeath: (() -> Unit)? = null

    fun attachTunnelOwner(host: IBinder, onDeath: () -> Unit) {
        hostBinder = host
        onUnexpectedDeath = onDeath
    }

    fun detachTunnelOwner() {
        hostBinder = null
        onUnexpectedDeath = null
    }

    private val context: Context get() = AndroidXrayEnvironment.applicationContext

    /**
     * Serialises whole engine operations — validate, start, stop, kill — against each other.
     *
     * [connectionMutex] only ever covered the bind inside [binder], so a kill could still land BETWEEN
     * a start's bind and its START transaction, killing the very process the start was talking to (or a
     * suicide landing on a freshly-bound one). This holds for the entire operation, so a kill waits for
     * an in-flight start to finish and vice versa. Always the OUTER lock; [connectionMutex] stays the
     * inner one taken by [binder], and the ordering never inverts, so there is no deadlock.
     */
    private val opMutex = Mutex()

    private val connectionMutex = Mutex()

    @Volatile
    private var engine: IBinder? = null

    /** Last pid the engine reported, from the current process generation. Null before the first call. */
    @Volatile
    private var enginePid: Int? = null

    /** Set while [restart] is tearing the engine down, so its death is not reported as a surprise. */
    @Volatile
    private var killing = false

    private var pendingConnection: CompletableDeferred<IBinder?>? = null

    @Volatile
    private var deathSignal: CompletableDeferred<Unit>? = null

    /** Whether a binding is currently outstanding, so [unbind] does not fire against nothing. */
    private var bound = false

    /**
     * Authoritative death signal. `onServiceDisconnected` arrives on the main thread and only for a
     * binding the framework still tracks; this fires for the process dying at all, which is the event
     * that actually matters — the tunnel has no engine from that instant.
     */
    private val deathRecipient = IBinder.DeathRecipient {
        OtcLog.log(LOG_TAG, "engine process died (requested=$killing)")
        engine = null
        deathSignal?.complete(Unit)
        if (!killing) onUnexpectedDeath?.invoke()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            OtcLog.log(LOG_TAG, "connected to :xray")
            runCatching { service?.linkToDeath(deathRecipient, 0) }
                .onFailure {
                    OtcLog.log(LOG_TAG, "linkToDeath failed: ${it.message}")
                    errorReporter.report(ErrorDomain.XRAY_IPC, it)
                }
            engine = service
            pendingConnection?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The process died. Expected during a recovery restart, unexpected otherwise; either way
            // the next call re-binds rather than failing, because Android keeps the ServiceConnection
            // registered and will call us again when the process comes back.
            OtcLog.log(LOG_TAG, "lost connection to :xray")
            engine = null
        }

        override fun onBindingDied(name: ComponentName?) {
            OtcLog.log(LOG_TAG, "binding to :xray died")
            engine = null
        }

        override fun onNullBinding(name: ComponentName?) {
            // Cannot happen unless onBind returns null, which ours never does — but an unnoticed null
            // binding would hang every future call on a connection that is never coming.
            OtcLog.log(LOG_TAG, "NULL binding from :xray — engine unusable")
            pendingConnection?.complete(null)
        }
    }

    override suspend fun validate(rawConfig: String): XrayValidationResult = opMutex.withLock {
        val response = invoke(IpcRequest(XrayIpc.METHOD_VALIDATE, rawConfig))
            ?: return XrayValidationResult.Invalid("Configuration engine is unavailable")
        if (!response.ok) {
            return XrayValidationResult.Invalid(response.error ?: "Configuration engine failed")
        }
        return response.validation?.toDomain()
            ?: XrayValidationResult.Invalid("Configuration engine returned nothing")
    }

    /**
     * Meaningless across a process boundary — a descriptor number is only valid in the process that
     * holds it. The tunnel path uses [startOnTun], which sends the descriptor itself.
     */
    override suspend fun setTunFd(fd: Int) =
        error("setTunFd(Int) cannot cross a process boundary; use startOnTun")

    override suspend fun start(xrayJson: String): XrayRunResult =
        XrayRunResult.Failure("The engine needs a tun descriptor; use startOnTun")

    /**
     * Hand the engine a dup of the tun and start it, in one transaction.
     *
     * One call rather than set-then-start on purpose: two calls leave a window in which the engine
     * holds a descriptor it has no config for, and — worse — a failed start between them would leave
     * that descriptor owned by a process nobody is about to restart.
     *
     * [tun] is consumed by the transaction; the caller's copy is closed on return either way.
     */
    suspend fun startOnTun(tun: ParcelFileDescriptor, xrayJson: String): XrayRunResult =
        tun.use {
            opMutex.withLock {
                val response = invoke(IpcRequest(XrayIpc.METHOD_START, xrayJson), it)
                    ?: return@withLock XrayRunResult.Failure("Engine process is unavailable")
                if (!response.ok) return@withLock XrayRunResult.Failure(response.error ?: "Engine failed to start")
                response.run?.toDomain() ?: XrayRunResult.Failure("Engine returned nothing")
            }
        }

    override suspend fun stop(): XrayRunResult = opMutex.withLock {
        // Nothing bound means nothing to stop. Without this check `invoke` -> `binder()` ->
        // `bindService(BIND_AUTO_CREATE)` STARTS the engine process for the sole purpose of telling it
        // to stop, and then it is killed again: seven fork/stop/suicide cycles in seven seconds appear
        // in the field log, ~500ms each, all of them pointless.
        if (engine?.isBinderAlive != true) {
            OtcLog.log(LOG_TAG, "stop: no engine bound — nothing to stop")
            return XrayRunResult.Success
        }
        val response = invoke(IpcRequest(XrayIpc.METHOD_STOP))
            ?: return XrayRunResult.Failure("Engine process is unavailable")
        if (!response.ok) return XrayRunResult.Failure(response.error ?: "Engine failed to stop")
        return response.run?.toDomain() ?: XrayRunResult.Failure("Engine returned nothing")
    }


    /**
     * Throw the engine process away. The next call brings up a fresh one.
     *
     * This is what T2 recovery became. It replaces killing OUR OWN process and having an AlarmManager
     * resurrect it — a design that existed only because the thing being killed was also the thing
     * holding the tun. Now the tun stays open in this process, so the restart is invisible: no icon
     * blink, no re-establish, no unprotected window, and nothing to schedule.
     *
     * Why a kill and not `stopService`/`startService`: nothing is ever deleted from hysteria2's client-pool map, and its janitor only closes clients
whose connection has gone Inactive — which one kept alive by a 3s keepalive never does.
     * Android keeps a serviced process cached, so a service restart would very likely land back in the
     * SAME process with that session still running — precisely the failure the restart exists to
     * clear. Only process death is a guarantee.
     */
    suspend fun killEngineProcess(): Boolean = opMutex.withLock { connectionMutex.withLock {
            val binder = engine
            if (binder == null) {
                // Nothing to kill. Still unbind, in case a binding is outstanding against a process we
                // have already lost track of, so the next call starts from a clean slate.
                unbind()
                OtcLog.log(LOG_TAG, "kill: no engine bound — next call will start a fresh one")
                return@withLock true
            }
            killing = true
            val died = CompletableDeferred<Unit>()
            deathSignal = died
            try {
                requestSuicide(binder)
                val gone = withTimeoutOrNull(DEATH_TIMEOUT_MS) { died.await() } != null
                if (!gone) forceKill(binder)
                // Unbind AFTER the process is gone, not before: while a BIND_AUTO_CREATE binding is
                // outstanding the framework may bring the service back on its own schedule, racing the
                // fresh start we are about to do. Dropping the binding here means the next bind is the
                // only thing that can create it.
                unbind()
                OtcLog.log(LOG_TAG, "kill: engine process replaced (cleanKill=$gone)")
                gone
            } finally {
                killing = false
                deathSignal = null
            }
    } }

    private fun requestSuicide(binder: IBinder) {
        runCatching {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrayIpc.ENGINE_DESCRIPTOR)
                // FLAG_ONEWAY is not an optimisation here, it is required: the receiver dies without
                // replying, so a blocking call would raise DeadObjectException on success.
                binder.transact(XrayIpc.TX_SUICIDE, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }.onFailure { OtcLog.log(LOG_TAG, "suicide request threw: ${it.message}") }
    }

    /**
     * Last resort for an engine that took the suicide call and did not die — a goroutine wedged inside
     * libXray, say, which is exactly the state a restart exists to clear.
     *
     * Guarded on the binder still being alive. That is what makes the pid safe to use: a live binder
     * proves the process it belongs to is still running, so the number cannot yet have been recycled
     * onto somebody else's process.
     */
    private fun forceKill(binder: IBinder) {
        val pid = enginePid
        if (pid == null || !binder.isBinderAlive) {
            OtcLog.log(LOG_TAG, "force kill skipped (pid=$pid alive=${binder.isBinderAlive})")
            return
        }
        OtcLog.log(LOG_TAG, "engine ignored suicide — killing pid=$pid")
        runCatching { Process.killProcess(pid) }
            .onFailure { OtcLog.log(LOG_TAG, "force kill threw: ${it.message}") }
    }

    private fun unbind() {
        // Guarded: unbindService against a connection that was never registered throws
        // IllegalArgumentException, and [restart] reaches here on paths where nothing is bound.
        if (bound) {
            runCatching { context.unbindService(connection) }
                .onFailure { OtcLog.log(LOG_TAG, "unbind threw: ${it.message}") }
        }
        engine = null
        enginePid = null
        bound = false
    }

    private suspend fun invoke(request: IpcRequest, tun: ParcelFileDescriptor? = null): IpcResponse? {
        val binder = binder() ?: return null
        val requestJson = XrayIpcPayloads.json.encodeToString(IpcRequest.serializer(), request)
        // Binder transactions block, and the far side runs libXray's parser or its whole startup, so
        // this is never a main-thread call.
        // The transaction itself must not throw out of here. `transact` and `readException` raise
        // DeadObjectException the moment the engine process is gone — which is precisely the situation
        // the recovery ladder exists for. Unhandled, that exception unwound all the way out of the
        // tunnel job and skipped the `keepAliveLoop()` call that follows the ladder, leaving a live tun
        // with no engine, a Connected status and no watcher at all until some unrelated callback fired.
        // Returning null is the contract every caller here already handles.
        val responseJson = withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                XrayIpcTransport.writeInvoke(data, requestJson, hostBinder, tun)
                binder.transact(XrayIpc.TX_INVOKE, data, reply, 0)
                reply.readException()
                reply.readString()
            } catch (error: Exception) {
                OtcLog.log(LOG_TAG, "${request.method} transaction failed: ${error.javaClass.simpleName}: ${error.message}")
                errorReporter.report(ErrorDomain.XRAY_IPC, error)
                engine = null
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        } ?: return null
        return runCatching {
            XrayIpcPayloads.json.decodeFromString(IpcResponse.serializer(), responseJson.orEmpty())
        }.getOrElse {
            OtcLog.log(LOG_TAG, "unreadable response from :xray: ${it.message}")
            errorReporter.report(ErrorDomain.XRAY_IPC, it)
            null
        }?.also { response -> response.pid?.let { enginePid = it } }
    }

    private suspend fun binder(): IBinder? {
        engine?.takeIf { it.isBinderAlive }?.let { return it }
        return connectionMutex.withLock {
            engine?.takeIf { it.isBinderAlive }?.let { return@withLock it }
            val awaiting = CompletableDeferred<IBinder?>()
            pendingConnection = awaiting
            val intent = Intent(context, XrayEngineService::class.java)
            // BIND_IMPORTANT so the engine inherits this process's priority: it is where the tunnel
            // actually runs, and letting it be reaped ahead of us would mean a live tun with nothing
            // reading it. AUTO_CREATE starts the process if it is not up.
            val requested = runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            }.getOrElse {
                OtcLog.log(LOG_TAG, "bindService threw: ${it.message}")
                errorReporter.report(ErrorDomain.XRAY_IPC, it)
                false
            }
            if (!requested) {
                // bindService can return false and STILL have registered the connection, so the
                // binding has to be released or it leaks for the life of the process.
                runCatching { context.unbindService(connection) }
                OtcLog.log(LOG_TAG, "bindService refused — :xray will not start")
                return@withLock null
            }
            bound = true
            withTimeoutOrNull(BIND_TIMEOUT_MS) { awaiting.await() }.also {
                if (it == null) OtcLog.log(LOG_TAG, "timed out waiting for :xray to bind")
                pendingConnection = null
            }
        }
    }

    private companion object {
        /** Process start plus onCreate. Generous — the cost of being wrong is a spurious failure. */
        const val BIND_TIMEOUT_MS = 10_000L

        /**
         * How long a process gets to act on its own suicide before we kill it by pid. Short: it has
         * nothing to do but flush a log and call killProcess, and every millisecond here is a
         * millisecond the tunnel has no engine.
         */
        const val DEATH_TIMEOUT_MS = 2_000L
    }
}
