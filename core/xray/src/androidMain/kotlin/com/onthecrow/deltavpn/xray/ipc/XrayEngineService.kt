package com.onthecrow.deltavpn.xray.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.onthecrow.deltavpn.xray.AndroidVpnSocketProtector
import com.onthecrow.deltavpn.xray.AndroidXrayEnvironment
import com.onthecrow.deltavpn.xray.OtcLog
import com.onthecrow.deltavpn.xray.PlatformXrayEngine
import com.onthecrow.deltavpn.xray.XrayRunResult
import com.onthecrow.deltavpn.xray.protect.ChoosingSocketProtector
import com.onthecrow.deltavpn.xray.protect.LocalSocketProtector
import com.onthecrow.deltavpn.xray.protect.RemoteSocketProtector
import kotlinx.coroutines.runBlocking

private const val LOG_TAG = "XRAYSVC"

/**
 * Hosts libXray, and nothing else, in the `:xray` process.
 *
 * The point of the separate process is that it can be killed, because nothing is ever deleted from hysteria2's client-pool map, and its janitor only closes clients
whose connection has gone Inactive — which one kept alive by a 3s keepalive never does.
 * A stopped engine therefore leaves an immortal session still talking to the server, and only process
 * death reaps it. Before the split that meant killing the process that also held the tun, which tore
 * the VPN down and blinked the system's VPN icon. Here it costs nothing visible: the app process keeps
 * the tun open across the restart.
 *
 * Deliberately NOT a foreground service. It does not need its own notification; it stays alive because
 * the app process binds it with BIND_IMPORTANT while running its own foreground service. Making it a
 * second foreground service would mean a second `specialUse` justification under manual review in the
 * most scrutinised category on Play, for no capability we lack.
 */
class XrayEngineService : Service() {
    private val engine by lazy { PlatformXrayEngine() }

    /**
     * Set before the engine is ever asked to start, from the same [TX_INVOKE] transaction that hands
     * over the tun descriptor. Volatile because the dialer callback reads it from Go's threads.
     */
    @Volatile
    private var hostBinder: IBinder? = null

    /** The raw tun descriptor this process currently owns, or null before the first start. */
    private var currentTunFd: Int? = null

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            when (code) {
                XrayIpc.TX_INVOKE -> {
                    val call = XrayIpcTransport.readInvoke(data)
                    // A binder owned by the app process, for the protect fallback. Carried by the same
                    // call rather than registered separately, so there is no window in which the engine
                    // could dial with no way to protect what it dials.
                    call.host?.let { hostBinder = it }
                    val response = handle(call.requestJson, call.tun)
                    reply?.writeNoException()
                    reply?.writeString(response)
                    true
                }
                XrayIpc.TX_SUICIDE -> {
                    data.enforceInterface(XrayIpc.ENGINE_DESCRIPTOR)
                    OtcLog.log(LOG_TAG, "suicide requested — killing :xray (pid=${Process.myPid()})")
                    // Flush first: the tail of the engine log belongs to whatever recovery asked for
                    // this, which is exactly the part worth reading afterwards.
                    OtcLog.flushBlocking()
                    Process.killProcess(Process.myPid())
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
    }

    override fun onCreate() {
        super.onCreate()
        // This process runs no Koin graph and no Application init, so the engine's own dependencies
        // have to be established here. Unconditional: the environment holds a lateinit Context, and a
        // miss would surface as an UninitializedPropertyAccessException swallowed by a runCatching
        // somewhere downstream and reported as an ordinary config error.
        AndroidXrayEnvironment.initialize(this)
        // OtcLog needs no init of its own — it reads the context back out of the environment above,
        // and stamps its own "process start" banner on the first line it writes.
        OtcLog.log(LOG_TAG, "engine process up")
        installProtector()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        OtcLog.log(LOG_TAG, "onDestroy (:xray)")
        super.onDestroy()
    }

    /**
     * Installed in `onCreate`, before anything can ask the engine to dial. The choice between
     * protecting locally and asking the host is made on the first socket, not here — see
     * [ChoosingSocketProtector].
     */
    private fun installProtector() {
        AndroidVpnSocketProtector.install(
            ChoosingSocketProtector(
                local = LocalSocketProtector(),
                remoteProvider = { hostBinder?.let(::RemoteSocketProtector) },
            ),
        )
    }

    private fun handle(requestJson: String, tun: ParcelFileDescriptor?): String {
        val request = runCatching {
            XrayIpcPayloads.json.decodeFromString(IpcRequest.serializer(), requestJson)
        }.getOrElse {
            tun?.let { runCatching { it.close() } }
            return encode(IpcResponse(ok = false, error = "Malformed request: ${it.message}"))
        }
        return runCatching {
            when (request.method) {
                XrayIpc.METHOD_PING -> IpcResponse(ok = true, pid = Process.myPid())
                XrayIpc.METHOD_VALIDATE -> IpcResponse(
                    ok = true,
                    validation = runBlocking { engine.validate(request.arg.orEmpty()) }.toIpc(),
                )
                XrayIpc.METHOD_START -> IpcResponse(
                    ok = true,
                    run = start(request.arg.orEmpty(), tun).toIpc(),
                    // Carried on every start so the host always holds a pid from the CURRENT process
                    // generation — the fallback kill must never be aimed at a number learned before a
                    // restart.
                    pid = Process.myPid(),
                )
                XrayIpc.METHOD_STOP -> IpcResponse(ok = true, run = runBlocking { engine.stop() }.toIpc())
                else -> {
                    tun?.let { runCatching { it.close() } }
                    IpcResponse(ok = false, error = "Unknown method '${request.method}'")
                }
            }
        }.getOrElse { error ->
            OtcLog.log(LOG_TAG, "${request.method} threw: ${error.javaClass.simpleName}: ${error.message}")
            IpcResponse(ok = false, error = "${error.javaClass.simpleName}: ${error.message}")
        }.let(::encode)
    }

    /**
     * Take ownership of the tun descriptor and start the engine on it.
     *
     * The descriptor arrives as a dup of the app process's tun, made by the kernel when the binder
     * transaction crossed. It is OURS now: libXray reads the raw number out of the config `env`, so the
     * wrapper is detached rather than closed, and the previous one is closed here — in a process meant
     * to survive several of these, letting them accumulate would run the tun's owner out of descriptors.
     */
    private fun start(configJson: String, tun: ParcelFileDescriptor?): XrayRunResult {
        if (tun == null) return XrayRunResult.Failure("No tun descriptor was sent with the start request")
        val fd = runCatching { tun.detachFd() }.getOrElse {
            return XrayRunResult.Failure("Unusable tun descriptor: ${it.message}")
        }
        closeCurrentTunFd()
        currentTunFd = fd
        OtcLog.log(LOG_TAG, "received tun fd=$fd")
        return runBlocking {
            engine.setTunFd(fd)
            engine.start(configJson)
        }
    }

    private fun closeCurrentTunFd() {
        val previous = currentTunFd ?: return
        currentTunFd = null
        runCatching { ParcelFileDescriptor.adoptFd(previous).close() }
            .onFailure { OtcLog.log(LOG_TAG, "closing the previous tun fd threw: ${it.message}") }
    }

    private fun encode(response: IpcResponse): String =
        XrayIpcPayloads.json.encodeToString(IpcResponse.serializer(), response)
}
