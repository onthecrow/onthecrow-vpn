package com.onthecrow.onthecrowvpn.vpn

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onthecrow.onthecrowvpn.vpn.log.DebugLog
import com.onthecrow.onthecrowvpn.xray.AndroidVpnSocketProtector
import com.onthecrow.onthecrowvpn.xray.AndroidXrayEnvironment
import com.onthecrow.onthecrowvpn.xray.OtcLog
import com.onthecrow.onthecrowvpn.xray.PlatformXrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import com.onthecrow.onthecrowvpn.xray.XrayRunResult
import com.onthecrow.onthecrowvpn.xray.protectFdCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class OnthecrowVpnService : VpnService() {
    // CoroutineExceptionHandler catches anything that escapes the runCatching guards (so a thrown
    // recovery/keepalive coroutine is logged with its stack instead of vanishing into Logcat in Doze).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { ctx, error ->
            logd("UNCAUGHT coroutine error in $ctx: ${error.stackTraceToString()}")
        },
    )
    private val xrayEngine = PlatformXrayEngine()
    private val sanitizer = XrayConfigSanitizer()
    private val operationMutex = Mutex()

    // The tun interface, built once per process by [runConnect]. xray gets a *dup* of this fd
    // ([xrayTunFd]). It lives and dies with the process — there is no path that tears it down and
    // rebuilds it in place, because xray cannot actually be stopped in-process (see [stopXray]).
    private var tunInterface: ParcelFileDescriptor? = null
    private var xrayTunFd: Int? = null

    // When the current tun was established. [probeTunnel] refuses to answer inside the grace window
    // after it, because netd installs this VPN's per-UID routing rules asynchronously and a probe fired
    // in that window leaves on the PHYSICAL network — answering "healthy" for a dead tunnel.
    @Volatile
    private var lastEstablishAt = 0L

    // TEMP (diagnosis): the emitted xray config is dumped once per process, not on every re-dial.
    @Volatile
    private var runtimeJsonLogged = false

    // Guards the recovery ladder: a second trigger while one is running is dropped rather than queued —
    // the running ladder's own probes already observe the current state.
    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)

    // The config to refresh with while a tunnel is active (null when disconnected).
    @Volatile
    private var activeXrayJson: String? = null

    // Per-app routing for the current session (from the CONNECT intent), reused when a recovery
    // rebuilds the tun so the same exclusions stay applied. At most one is non-empty.
    @Volatile
    private var activeDisallow: List<String> = emptyList()

    @Volatile
    private var activeAllow: List<String> = emptyList()

    // Network monitor: default callback (API 31+, reads the VPN's real underlying network) or a
    // NOT_VPN fallback (< 31). Refreshes xray when the underlying physical network actually changes.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastUnderlying: Network? = null

    @Volatile
    private var underlyingSeeded = false

    private val mtu = 1500

    private var screenReceiver: BroadcastReceiver? = null
    private var idleModeReceiver: BroadcastReceiver? = null

    // Signature of the current underlying link's properties, so a route change that keeps the same netId
    // is still detected without reacting to routine DNS/MTU churn.
    @Volatile
    private var lastLinkSignature: String? = null

    /**
     * The user (or a revocation) asked us to stop. Read by the recovery ladder at every wait point so a
     * teardown does not queue behind up to half a minute of NonCancellable patience. Volatile: written
     * on the main thread from `onStartCommand`, read from the recovery coroutine.
     */
    @Volatile
    private var disconnecting = false

    // Live screen state. It no longer gates recovery — only the keepalive cadence.
    @Volatile
    private var screenOn = true

    // The single tunnel-health state machine (recover + keepalive); alive while screen-on + connected.
    private var tunnelJob: Job? = null

    // Persisted connect params so this :vpn process can self-reconnect after a crash / system kill.
    private val paramsStore by lazy { ConnectionParamsStore(this) }

    /** Authoritative per-app routing, written by the main process. See [SplitTunnelRoutingStore]. */
    private val routingStore by lazy { SplitTunnelRoutingStore(this) }

    // A retry is waiting out its backoff. Trigger events consult this to cut the wait short.
    @Volatile
    private var retryPending = false
    private var retryJob: Job? = null

    /** Answers a status re-query from a main process that restarted while the tunnel was up. */
    @Volatile
    private var lastPublishedStatus: ConnectionStatus = ConnectionStatus.Disconnected
    private var statusRequestReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // We run in the ":vpn" process, where the Application does NOT bring up the app graph — set up
        // the small env libXray needs (datDir) ourselves.
        AndroidXrayEnvironment.initialize(this)
        AndroidVpnEnvironment.initialize(this)
        // TEMP (diagnosis): route any common-code logs running in this process into the same file.
        DebugLog.setSink { tag, message -> OtcLog.log(tag, message) }
        statusRequestReceiver = VpnStatusBroadcast.registerStatusRequests(this) {
            logd("status re-query from main process — replying $lastPublishedStatus")
            VpnStatusBroadcast.send(this, lastPublishedStatus)
        }
        logd("onCreate (:vpn process) sdk=${Build.VERSION.SDK_INT}")
        logPreviousExitReasons()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logd("onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_CONNECT -> {
                startAsForeground()
                val fromIntent = intent.getStringExtra(EXTRA_XRAY_JSON)
                // A recovery restart re-enters here carrying no payload (the PendingIntent was built
                // before the kill, and this is a brand-new process with no in-memory state), so fall
                // back to the persisted params — in a fresh process they ARE the source of truth.
                val restored = if (fromIntent.isNullOrBlank()) paramsStore.load() else null
                val xrayJson = fromIntent ?: restored?.xrayJson
                activeDisallow = intent.getStringArrayListExtra(EXTRA_DISALLOW)
                    ?: restored?.disallow ?: emptyList()
                activeAllow = intent.getStringArrayListExtra(EXTRA_ALLOW)
                    ?: restored?.allow ?: emptyList()
                if (restored != null) logd("connect: restored persisted params after a recovery restart")
                // Persist for crash self-heal (cleared on deliberate teardown / fatal failure).
                if (!xrayJson.isNullOrBlank()) {
                    paramsStore.save(ConnectionParams(xrayJson, activeDisallow, activeAllow))
                }
                scope.launch { runConnect(xrayJson) }
            }
            // Set BEFORE launching, and outside [operationMutex], because a recovery may be holding that
            // mutex right now: the ladder is NonCancellable and can legitimately sit in its patience
            // window for half a minute, and the user must not watch a dead Disconnect button for that
            // long. The ladder polls this flag and stands down.
            ACTION_DISCONNECT -> {
                disconnecting = true
                scope.launch { runUserDisconnect() }
            }
            // Remote revocation: same teardown as disconnect (Android has no persisted system profile).
            ACTION_REVOKE -> {
                disconnecting = true
                scope.launch { runUserDisconnect() }
            }
            // intent == null (and so action == null): START_STICKY recreated us after a crash / system
            // kill. The in-memory config is gone, so self-heal from the persisted params. We MUST call
            // startForeground promptly (FGS start timeout), and a fresh process means a clean hysteria
            // pool — so reconnecting here is safe. No persisted params → nothing to restore, stand down.
            else -> {
                val saved = paramsStore.load()
                if (saved != null) {
                    logd("sticky restart — restoring tunnel from persisted config")
                    startAsForeground()
                    activeDisallow = saved.disallow
                    activeAllow = saved.allow
                    scope.launch { runConnect(saved.xrayJson) }
                } else {
                    logd("sticky restart — no persisted config; standing down")
                    stopSelf()
                }
            }
        }
        return Service.START_STICKY
    }

    /**
     * The system tore the VPN down externally: the user revoked us in Settings/Quick Settings, or
     * another VPN app took over. TERMINAL, never recoverable — our authorisation is gone, so retrying
     * would only fight the user or the other app. Full teardown, params cleared.
     */
    override fun onRevoke() {
        logd("onRevoke (external teardown) — terminal, not recoverable")
        scope.launch { runUserDisconnect() }
    }

    override fun onDestroy() {
        logd("onDestroy")
        runBlocking { operationMutex.withLock { stopMonitoring(); stopTunnel() } }
        unregisterMonitoring()
        statusRequestReceiver?.let { runCatching { unregisterReceiver(it) } }
        statusRequestReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    // A FRESH establish: build a new tun and start xray. This is the ONLY way the tunnel ever comes up —
    // a user connect, a crash self-heal, or a recovery restart, each in a brand-new process. There is
    // no in-process path, because xray's tun readers outlive [stopXray] and would fight the new
    // instance for packets.
    private suspend fun runConnect(xrayJson: String?) {
        operationMutex.withLock {
            val configJson = xrayJson ?: activeXrayJson
            logd("runConnect: hasConfig=${!configJson.isNullOrBlank()} underlying=$lastUnderlying")
            if (configJson.isNullOrBlank()) {
                fail("No validated configuration is available")
                return
            }
            activeXrayJson = configJson
            disconnecting = false
            // Re-read the physical network from the system instead of trusting cached callback state
            // (callbacks are dropped across Doze), then advertise it. On this path the cache is empty in
            // a fresh process, so the scan runs and seeds it.
            refreshUnderlyingFromSystem()
            // Re-read the routing at the last possible moment. The lists carried in the CONNECT intent
            // (or restored from persisted params) are a snapshot from whenever that path last ran,
            // and this service re-establishes on paths the main process never sees. The store is
            // authoritative; the snapshot is only a fallback for a first run that predates it.
            routingStore.load()?.let { routing ->
                if (routing.disallow.toList() != activeDisallow || routing.allow.toList() != activeAllow) {
                    logd("split-tunnel: refreshed from store (was disallow=$activeDisallow allow=$activeAllow)")
                }
                activeDisallow = routing.disallow.toList()
                activeAllow = routing.allow.toList()
            }
            logd("connect: establishing tunnel")
            runCatching {
                stopTunnel()
                AndroidVpnSocketProtector.setProtector(::protectSocket)
                tunInterface = Builder()
                    .setSession("Onthecrow VPN")
                    .setMtu(mtu)
                    .addAddress(TUN_ADDRESS, 32)
                    .addRoute("0.0.0.0", 0)
                    // Claim IPv6 WITHOUT giving the interface a v6 address. Android only routes the
                    // address families a VPN declares, so without this every v6-capable app talks
                    // straight past the tunnel — and on a dual-stack network Happy Eyeballs actively
                    // prefers v6, so that is most of them.
                    //
                    // No address is deliberate. With the route but no source address the kernel has
                    // nothing to bind, so a v6 connect() fails instantly with ENETUNREACH and Happy
                    // Eyeballs falls straight through to IPv4 — which the tunnel does carry. Giving
                    // the tun a v6 address instead would make apps PREFER a path we cannot actually
                    // forward, and they would wait out a timeout on every connection before falling
                    // back. Carrying v6 properly needs the outbound and the server to support it,
                    // which is a separate change.
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    // NB: we deliberately do NOT exclude ourselves from the tunnel — our own traffic
                    // routes through it so the health probe ([probeTunnel]) actually tests the tunnel.
                    // xray's upstream sockets are protected individually via protectFd (no loop risk).
                    .apply { applySplitTunnel(this) }
                    .establish()
                    ?: error("Android refused to establish VPN interface")
                // netd installs this VPN's per-UID routing rules asynchronously AFTER establish()
                // returns; [probeTunnel] must not answer inside that window (see PROBE_ESTABLISH_GRACE_MS).
                lastEstablishAt = SystemClock.elapsedRealtime()
                logd("tun established: fd=${tunInterface?.fd}")
                // Re-advertise AFTER establish, not before: the declaration hangs off the VPN's
                // NetworkAgent, and establish() builds a new one. Doing it in the connect prologue left
                // the fresh agent with no underlying network declared at all.
                applyUnderlyingNetworks(lastUnderlying)
                when (val result = startXrayOnTun(configJson)) {
                    XrayRunResult.Success -> {
                        // Deliberately NOT Connected yet. xray having started means the config parsed,
                        // not that a packet can leave the phone: in the field 15 of 99 Connected
                        // broadcasts were followed ~1.1s later by the first probe failing, and one of
                        // them was a tunnel that never carried a single packet in its seven-second life.
                        // The user was told they were protected while they were not. Connected is now
                        // claimed only by [noteTunnelHealthy], on a confirmed round-trip.
                        publishStatus(ConnectionStatus.Connecting)
                        logd("connect: xray up — awaiting first confirmed probe")
                        startMonitoring()
                    }
                    is XrayRunResult.Failure -> scheduleRetry("xray start failed: ${result.message}")
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    logd("runConnect CANCELLED: ${error.message}")
                    throw error
                }
                logd("runConnect FAILED: ${error.stackTraceToString()}")
                scheduleRetry(error.message ?: "Failed to start VPN")
            }
        }
    }

    /**
     * Give xray a fresh **dup** of the held-open tun fd and start it. We dup so xray can be
     * stopped/restarted (on every re-dial) without ever closing the master [tunInterface] — the
     * virtual interface, and the OS routing of app traffic into it, stay up across the handover.
     */
    private suspend fun startXrayOnTun(configJson: String): XrayRunResult {
        val master = tunInterface ?: return XrayRunResult.Failure("Tun interface is not established")
        val fd = master.dup().detachFd()
        xrayTunFd = fd
        xrayEngine.setTunFd(fd)
        // TEMP (diagnosis): verbose xray log into a pullable file. logLevel=debug surfaces the upstream
        // dial / hysteria QUIC reconnect chatter we need. Revert logLevel/errorLogPath when fixed.
        val xrayLogPath = prepareXrayLogFile()
        // `warning` in release left us blind exactly where it hurt: a day of field logs contains nothing
        // from xray but its version banner, so when a redial produced a tunnel that carried nothing
        // there was no way to see whether it had failed to dial, failed to resolve, or never been handed
        // a packet. `info` is the level that carries the tun fd it attached to and the dial errors,
        // without `debug`'s per-connection access chatter — the expensive part, measured at 110-183
        // lines/minute, each a synchronous write to FUSE-backed external storage.
        val logLevel = if (OtcLog.isDebugBuild) "debug" else "info"
        val runtimeJson = sanitizer.withTunInbound(
            configJson,
            mtu = mtu,
            logLevel = logLevel,
            errorLogPath = xrayLogPath,
            // The dominant term in every recovery we have measured. Left at xray's defaults, a dead
            // QUIC session is not noticed for ~30s of awake time and nothing re-dials before then, so
            // both a handover and a Doze exit cost 19-25s of no traffic no matter what we do. Ten
            // seconds bounds that, and the 3s keepalive is what makes ten seconds safe: without it QUIC
            // sends nothing on an idle connection and a healthy tunnel would time itself out.
            //
            // The cost is radio wakeups every 3s while the CPU is awake. It buys nothing during deep
            // sleep either way — the timer does not advance while suspended, which is precisely why a
            // path that dies during Doze is only noticed after waking.
            quicLiveness = XrayConfigSanitizer.QuicLiveness(
                maxIdleTimeoutSeconds = QUIC_MAX_IDLE_TIMEOUT_S,
                keepAlivePeriodSeconds = QUIC_KEEPALIVE_PERIOD_S,
            ),
        )
        // TEMP (diagnosis): fingerprint of the client credential actually handed to xray — verifies a
        // config switch really reaches the engine (per-client id/password/auth differ; prefix only).
        val cred = CREDENTIAL_REGEX.find(runtimeJson)?.groupValues?.get(2)
        logd("xray start: tunFd=$fd client-credential=${cred?.take(6) ?: "?"}… (xray loglevel=$logLevel)")
        // TEMP (diagnosis): dump the config we actually hand to xray, once per process, so the emitted
        // transport settings can be checked against what we think we generated.
        //
        // Credentials are redacted. This log ships in release builds and lives in /Android/data, which
        // the user and any MANAGE_EXTERNAL_STORAGE holder can read — writing a VLESS uuid or hysteria2
        // password there in clear would hand over the account. Redaction costs nothing diagnostically:
        // the question this answers is about transport settings, and the fingerprint line above still
        // identifies WHICH config is live.
        if (OtcLog.isDebugBuild && !runtimeJsonLogged) {
            runtimeJsonLogged = true
            val redacted = runtimeJson.replace(CREDENTIAL_REGEX) { m -> "${m.groupValues[1]}***\"" }
            logd("xray runtimeJson (${runtimeJson.length}B): $redacted")
        }
        val started = SystemClock.elapsedRealtime()
        val result = xrayEngine.start(runtimeJson)
        logd("xray start result: $result (${SystemClock.elapsedRealtime() - started}ms)")
        return result
    }

    /**
     * TEMP (diagnosis): xray writes its own log via libXray (Go), which on some devices' FUSE-emulated
     * external storage produces a file that `adb pull` / file managers can't read ("Permission denied").
     * So we create the file from the APP first — Go then opens the EXISTING file in append mode and
     * never re-creates or re-chmods it, so it inherits the same app-owned, pullable attributes as
     * vpn-debug.log. [reset] only on a fresh user connect (not on re-dials/recoveries), so one whole
     * session — including every reconnect attempt — accumulates in one pullable file.
     */
    /**
     * Point xray's own error log at the SAME file everything else writes to, so a user sharing their
     * log shares one file with the whole story in it.
     *
     * Deliberately does NOT truncate: [OtcLog] owns the size cap and truncates IN PLACE, because
     * deleting the file out from under xray's open append handle would silently swallow its output
     * for the life of the process.
     */
    private fun prepareXrayLogFile(): String? = runCatching {
        OtcLog.logFile(this).apply {
            if (!exists()) {
                parentFile?.mkdirs()
                createNewFile()
            }
            // World-readable so `adb pull` and file managers still work on a debug build; the release
            // path for getting this file is the share sheet in settings.
            runCatching { setReadable(true, false) }
        }.absolutePath
    }.getOrElse {
        logd("log file prepare FAILED: ${it.message}")
        null
    }

    /**
     * Apply per-app split-tunnel routing. disallow/allow are mutually exclusive. Invariants: never
     * disallow our own package, and in allow-list mode always tunnel our own package — otherwise the
     * health probe (running in this :vpn process) would test a direct connection instead of the tunnel.
     */
    private fun applySplitTunnel(builder: Builder) {
        when {
            activeDisallow.isNotEmpty() -> activeDisallow.filterNot { it == packageName }.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { logd("split-tunnel: cannot disallow $pkg: ${it.message}") }
            }
            activeAllow.isNotEmpty() -> {
                var applied = 0
                (activeAllow + packageName).distinct().forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg); applied++ }
                        .onFailure { logd("split-tunnel: cannot allow $pkg: ${it.message}") }
                }
                // The two branches fail in OPPOSITE directions, which is why only this one is checked.
                // A package we cannot DISALLOW merely stays in the tunnel — harmless. A package we
                // cannot ALLOW leaves the tunnel. If every selected app is gone (uninstalled since it
                // was picked) only our own package survives, and the result is a tunnel that carries
                // nothing but us while reporting Connected. Refuse to establish instead.
                if (applied <= 1) {
                    error("None of the apps selected for the VPN are installed any more")
                }
            }
        }
        if (activeDisallow.isNotEmpty() || activeAllow.isNotEmpty()) {
            logd("split-tunnel: disallow=$activeDisallow allow=$activeAllow")
        }
    }

    /**
     * Stop xray.
     *
     * NB this does NOT fully stop it. `proxy/tun.Handler` has no Close method, the always-on inbound
     * handler never closes `h.proxy`, and `AndroidTun.Close()` returns nil — so the gVisor reader
     * goroutines keep reading the tun fd for the life of the PROCESS. Every caller of this must
     * therefore end in process death; starting xray again in the same process gives you two stacks
     * competing for one tun queue and roughly half the packets vanish. That is why recovery restarts
     * the process instead of re-dialling in place.
     *
     * The dup'd fd is left open deliberately: closing it frees the fd number for the next `dup()`,
     * and the surviving reader would then be reading an unrelated socket. It is reclaimed when the
     * process dies, which is imminent by construction.
     */
    private suspend fun stopXray(): Boolean {
        val result = xrayEngine.stop()
        logd("xray stop: $result (dupFd=$xrayTunFd left open)")
        xrayTunFd = null
        return result is XrayRunResult.Success
    }

    /**
     * Re-dial xray on the EXISTING tun — the cheap repair, and the only one that does not make the
     * system VPN icon blink.
     *
     * This became possible with libXray v26.7.11: `AlwaysOnInboundHandler.Close` now calls
     * `common.Close(h.proxy)`, which reaches `proxy/tun.Handler.Close` and shuts the gVisor stack
     * down. Before that the tun readers outlived every stop, so a second instance split the packets
     * with a dead one and roughly half of them vanished — which is why this path was deleted once.
     *
     * The tun itself is never touched, so `establish()` is not called again, the VPN network is never
     * torn down, and nothing escapes while the engine restarts. Returns false if xray could not be
     * stopped cleanly; the caller then falls through to the process restart, which remains the only
     * thing guaranteed to clear state that got stuck.
     */
    private suspend fun runRedial(): Boolean {
        val configJson = activeXrayJson ?: run {
            logd("runRedial: no active config")
            return false
        }
        if (tunInterface == null) {
            logd("runRedial: no tun interface")
            return false
        }
        if (!stopXray()) {
            logd("runRedial: xray did not stop cleanly — leaving it to the process restart")
            return false
        }
        val result = startXrayOnTun(configJson)
        logd("runRedial: $result")
        return result is XrayRunResult.Success
    }

    /**
     * Deliberate, terminal teardown: the user disconnected, or the VPN was revoked out from under us.
     *
     * The ONLY place that clears the persisted params — a recovery must never come through here, or it
     * destroys its own fallback config. It also must never emit `Disconnected` on a recovery path:
     * besides the above, that status drives `VpnSyncWorker` to reset its active key, so a genuine remote
     * config change arriving mid-recovery would be silently swallowed.
     */
    private suspend fun runUserDisconnect() {
        operationMutex.withLock {
            logd("runUserDisconnect")
            // Cancel any pending retry FIRST: the whole point of an uncapped retry policy is that only
            // the user stops it, so this is the one place that must actually stop it.
            retryPending = false
            retryJob?.cancel()
            retryJob = null
            paramsStore.clear()
            publishStatus(ConnectionStatus.Disconnecting)
            stopMonitoring()
            stopTunnel()
            publishStatus(ConnectionStatus.Disconnected)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Kill this ":vpn" process so xray-core's process-global connection state is gone: with
            // keepalive enabled a pool entry that is never re-dialled would otherwise stay alive
            // forever. Delayed so the Disconnected broadcast above dispatches first; stopSelf() has
            // already marked us stopped, so START_STICKY will not resurrect this kill.
            scheduleProcessDeath()
        }
    }

    private fun scheduleProcessDeath() {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                logd("killing :vpn process now")
                OtcLog.flushBlocking() // finalize the log file before the hard kill
                Process.killProcess(Process.myPid())
            },
            PROCESS_DEATH_DELAY_MS,
        )
    }

    /** Full teardown: stop xray AND close the tun interface (used on disconnect / fatal error). */
    private suspend fun stopTunnel() {
        stopXray()
        AndroidVpnSocketProtector.setProtector(null)
        tunInterface?.let { runCatching { it.close() }.onFailure { logd("stopTunnel: close failed: ${it.message}") } }
        if (tunInterface != null) logd("stopTunnel: tun interface closed")
        tunInterface = null
    }

    // ---- Resilience: one state machine guards tunnel health ----
    //
    // Every trigger (validated network change, link-property change, Doze exit, screen on, keepalive
    // failure) feeds ONE job, which either runs the recovery ladder (see [recover]) or settles into the
    // keepalive loop that re-enters it on silent death. The job runs regardless of screen state — a
    // handover while the screen is off is exactly the case that has to work — and is cancelled only on
    // disconnect.

    private enum class TunnelStart {
        /** Just connected & known healthy — go straight to keepalive (don't re-probe immediately). */
        KEEPALIVE_ONLY,

        /** Screen on / wake: probe first; recover only if the tunnel is actually dead. */
        PROBE_FIRST,

        /** Physical-network change: the old upstream socket is dead — recover straight away. */
        FORCE_RECOVER,
    }

    private fun startTunnelJob(reason: String, start: TunnelStart) {
        // A retry is sitting out its backoff and something just changed — that is exactly the moment
        // worth trying again, rather than waiting for a timer that knows nothing about the network.
        if (retryPending) {
            retryNow(reason)
            return
        }
        if (activeXrayJson == null) {
            logd("startTunnelJob($reason/$start) ignored — no active config")
            return
        }
        if (tunnelJob?.isActive == true) logd("startTunnelJob($reason): cancelling in-flight tunnel job")
        tunnelJob?.cancel()
        logd("startTunnelJob: reason=$reason start=$start")
        tunnelJob = scope.launch {
            when (start) {
                // recover() kills the process if it proceeds; if it's debounced it returns and we fall
                // through to keepAliveLoop so the (still-alive) process keeps watching.
                TunnelStart.FORCE_RECOVER -> recover(reason)
                TunnelStart.PROBE_FIRST -> when (probeTunnel(PROBE_TIMEOUT_MS)) {
                    true -> {
                        logd("$reason: tunnel already healthy")
                        noteTunnelHealthy()
                    }
                    false -> {
                        logd("$reason: no answer on first ask — handing to the ladder")
                        recover(reason)
                    }
                    null -> logd("$reason: no verdict — leaving it to the keepalive")
                }
                TunnelStart.KEEPALIVE_ONLY -> logd("$reason: starting keepalive watch")
            }
            keepAliveLoop()
        }
    }

    /**
     * The recovery ladder. Runs ENTIRELY inside this `:vpn` process.
     *
     * It never asks the main process for anything: for a backgrounded VPN the main process is normally
     * dead (measured: a 35-minute gap with the tunnel up), a runtime-registered receiver cannot start a
     * process, and even when it was merely cached, broadcasts to it were delivered 39-68s late. Routing
     * recovery through it was three stacked ways to fail.
     *
     *   T0  probe, patiently                 up to [T0_PATIENCE_MS]   is it dead, or just not up yet?
     *   T1  re-dial the engine on our tun    ~2s                      cheap, and so far never effective
     *   T2  restart the process              backed off, uncapped     the one that has always worked
     *
     * T0 owns most of the budget on purpose. Every probe failure ever recorded in the field was a
     * timeout rather than an error, so "no answer" cannot distinguish a broken path from one that has
     * not finished coming up — and the day the ladder was written that distinction was collapsed into
     * 1.9 seconds, which is far shorter than a cellular attach. The result was a repair loop that fired
     * on healthy tunnels, killed the process ~37 times a day, and in the one incident traced end to end
     * fixed nothing: the tunnel that finally worked was identical to the two that were killed before it,
     * just started 23 seconds later.
     *
     * The old note here claimed no in-process tier was possible because xray's tun readers outlive
     * `stopXray`. That was true of libXray 26.3.27 and is NOT true of 26.7.11: `tun.Handler.Close`
     * reaches `stackGVisor.Close`, which calls `endpoint.Attach(nil)` — that signals the dispatcher's
     * eventfd and joins the goroutine before returning. The reader really is gone. Why T1 nonetheless
     * produces a tunnel that carries nothing is still unexplained (0 for 2 in the field, with no
     * outbound socket created at all afterwards); the xray-side log that would answer it is only now
     * being kept in release builds.
     *
     * Runs NonCancellable under [operationMutex] with a wakelock held: a recovery interrupted halfway
     * (by a screen event, a competing trigger, or CPU suspend) leaves the tunnel worse off than one
     * that never started. The one exception is [disconnecting], polled at every wait point, because the
     * user must not wait out half a minute of patience for a teardown.
     */
    private suspend fun recover(reason: String, patienceMs: Long = T0_PATIENCE_MS) {
        if (activeXrayJson == null) return
        if (!recovering.compareAndSet(false, true)) {
            logd("recover($reason): a recovery is already running — ignored")
            return
        }
        val wakeLock = acquireRecoveryWakeLock()
        try {
            withContext(NonCancellable) {
                operationMutex.withLock { runLadder(reason, patienceMs) }
            }
        } finally {
            recovering.set(false)
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }

    private suspend fun runLadder(reason: String, patienceMs: Long) {
        val started = SystemClock.elapsedRealtime()
        val fdAtStart = protectFdCount.get()
        fun elapsed() = SystemClock.elapsedRealtime() - started
        fun fdDelta() = protectFdCount.get() - fdAtStart
        logd(
            "recover($reason): START screenOn=$screenOn protectFd=$fdAtStart " +
                "batteryOptIgnored=${isIgnoringBatteryOptimizations()}",
        )

        // T0 — ask the tunnel itself, patiently.
        //
        // The old T0 gave the tunnel 1.9s (two 600ms probes) before condemning it. That is shorter than
        // the thing it is usually measuring. A cellular bearer that has just attached is not carrying a
        // proxy handshake yet: in the field the gap between "cell appeared" and the first probe that
        // could succeed was 23 SECONDS, during which the ladder ran twice, killed the process twice, and
        // repaired nothing — the tunnel that finally worked was identical to the two that were killed,
        // it was simply started late enough. Every one of the 179 probe failures in a day was a timeout,
        // never a socket error, so "no answer" carries no information about whether the path is broken
        // or merely not up yet. The only way to tell them apart is to wait.
        //
        // So T0 now keeps asking across [T0_PATIENCE_MS], with the timeout growing per attempt: a fresh
        // handshake over a cold LTE bearer does not fit in 600ms, while a warm tunnel answers in ~110ms
        // and still exits on the first attempt. Patience costs nothing when the tunnel is alive.
        refreshUnderlyingFromSystem()
        var attempt = 0
        while (elapsed() < patienceMs) {
            if (disconnecting) return logd("recover($reason): standing down — user is disconnecting")
            val timeout = PROBE_TIMEOUT_STEPS_MS[attempt.coerceAtMost(PROBE_TIMEOUT_STEPS_MS.lastIndex)]
            if (probeTunnel(timeout) == true) {
                logd("recover($reason): T0 OK — alive after ${elapsed()}ms (attempt ${attempt + 1})")
                onRecoverySucceeded()
                return
            }
            attempt++
            delay(T0_PROBE_RETRY_DELAY_MS)
        }
        if (disconnecting) return logd("recover($reason): standing down — user is disconnecting")
        logd("recover($reason): T0 dead — $attempt probes over ${elapsed()}ms of ${patienceMs}ms patience")

        // T1 — re-dial the engine on the tun we already have. Costs no icon blink and no unprotected
        // window, because the tun is never rebuilt. Falls through if xray would not stop cleanly.
        //
        // On the record so far this rung has never once worked: 0 for 2 in the field, both times with no
        // outbound socket created at all after the redial (the engine is restarted and then nothing
        // reaches it). The mechanism is not established — xray's own log is where the answer would be,
        // and in release builds we were not keeping one. It stays because it is cheap now that T0 has
        // already spent its patience, and because the next log will settle whether to fix it or drop it.
        if (runRedial()) {
            for (wait in T1_PROBE_DELAYS_MS) {
                delay(wait)
                if (probeTunnel(PROBE_TIMEOUT_STEPS_MS.last()) == true) {
                    logd("recover($reason): T1 OK after ${elapsed()}ms protectFdDelta=${fdDelta()}")
                    onRecoverySucceeded()
                    return
                }
            }
        }

        // T2 — the fallback that always worked. Deliberately kept: if the engine ever fails to stop,
        // or a redial produces a tunnel that still will not carry traffic, only a fresh process is
        // guaranteed to clear whatever is stuck.
        if (disconnecting) return logd("recover($reason): standing down — user is disconnecting")
        logd("recover($reason): T1 dead (${elapsed()}ms protectFdDelta=${fdDelta()}) → T2 process restart")
        restartProcessForRecovery(reason)
    }

    /**
     * A confirmed round-trip through the tunnel. This — and nothing else — is what proves we are
     * connected, so it both clears the restart backoff and is the only thing that reports Connected.
     *
     * Called from EVERY successful probe, not just from inside the ladder. Resetting only on a ladder
     * success left the counter climbing across restarts that had in fact recovered: a fresh process
     * that reconnects and then probes clean never went through [recover], so its attempt count survived
     * and an unrelated failure an hour later inherited a five-minute backoff it had not earned.
     *
     * The prefs write is skipped when the counter is already zero, since this runs on every keepalive.
     */
    private fun noteTunnelHealthy() {
        retryPending = false
        val prefs = recoveryPrefs()
        if (prefs.getInt(KEY_RETRY_ATTEMPT, 0) != 0) {
            runCatching {
                prefs.edit()
                    .putInt(KEY_RETRY_ATTEMPT, 0)
                    .putLong(KEY_LAST_RECOVERY_OK_AT, SystemClock.elapsedRealtime())
                    .commit()
            }
        }
        publishStatus(ConnectionStatus.Connected)
    }

    private fun onRecoverySucceeded() = noteTunnelHealthy()

    /**
     * How long to wait before the restarting alarm fires, as a function of how many restarts in a row
     * have failed to produce a working tunnel.
     *
     * This used to be a flat 500ms at every attempt: [RETRY_BACKOFF_MS] existed but was reachable only
     * from [scheduleRetry], i.e. only when xray itself refused to start — a path that fired zero times
     * in a day of field logs, while the restart path fired 37 times. So the advertised 1s→5min backoff
     * was never once applied to the thing that actually restarts. The visible result was a self-feeding
     * kill loop: nine restarts in four minutes, at a fixed 8.2s period, on a network that never changed.
     *
     * Never gives up (only an explicit disconnect does that), it just stops spinning: a phone in a
     * tunnel or on a plane settles at one attempt per five minutes.
     */
    private fun restartDelayFor(attempt: Int): Long =
        RETRY_BACKOFF_MS[(attempt - 1).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]
            .coerceAtLeast(MIN_RESTART_DELAY_MS)

    /**
     * T2: restart this process, because only a fresh one actually stops xray — its tun readers and
     * process-global connection state (hysteria2's client manager is a package-level global that
     * closing the core instance never reaps).
     *
     * The restart is carried by an alarm holding a PendingIntent aimed explicitly at this service, so
     * it lands straight back in `:vpn` — no receiver hop, no main-process cold start, no dependence on
     * the main process existing. ORDER BELOW IS LOAD-BEARING.
     */
    private fun restartProcessForRecovery(reason: String) {
        val configJson = activeXrayJson
        if (configJson == null) {
            logd("T2 restart: no active config — standing down rather than restarting")
            return
        }
        val prefs = recoveryPrefs()
        // Counted, never capped. A network outage is not a reason to give up on the user's VPN — only
        // an explicit disconnect is. The count drives [restartDelayFor] and is reset by
        // [noteTunnelHealthy] on any confirmed probe.
        val attempt = prefs.getInt(KEY_RETRY_ATTEMPT, 0) + 1
        val delayMs = restartDelayFor(attempt)

        // 1. Persist FIRST: this process is about to be SIGKILLed. commit(), never apply().
        paramsStore.save(ConnectionParams(configJson, activeDisallow, activeAllow))
        runCatching {
            prefs.edit()
                .putInt(KEY_RETRY_ATTEMPT, attempt)
                .putLong(KEY_LAST_RESTART_AT, SystemClock.elapsedRealtime())
                .commit()
        }

        // 2. Schedule the restart while we are still alive to schedule it. ELAPSED_REALTIME_WAKEUP, not
        //    RTC: an RTC alarm moves when NTP corrects the clock after a Doze exit.
        val restartIntent = Intent(this, OnthecrowVpnService::class.java).setAction(ACTION_CONNECT)
        val pending = PendingIntent.getForegroundService(
            this,
            REQ_RESTART,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            true
        }.getOrElse { error ->
            logd("T2 restart: exact alarm refused (${error.message}) — falling back to inexact")
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending) }
            false
        }
        logd(
            "T2 restart: reason=$reason attempt=$attempt exactAlarm=$exact in ${delayMs}ms " +
                "batteryOptIgnored=${isIgnoringBatteryOptimizations()}",
        )

        // 3. stopSelf() BEFORE the kill, so AMS does not also schedule a START_STICKY restart racing our
        //    alarm for the same component (that path carries an escalating ×4 backoff). A restart we did
        //    NOT plan — an xray panic — still gets the sticky restart, which is the net we want.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        OtcLog.flushBlocking()
        Process.killProcess(Process.myPid())
    }

    private fun acquireRecoveryWakeLock(): PowerManager.WakeLock? = runCatching {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(RECOVERY_WAKELOCK_TIMEOUT_MS)
        }
    }.getOrElse {
        logd("recovery wakelock unavailable: ${it.message}")
        null
    }

    // Load-bearing for three separate grants (exact-alarm eligibility, allow-while-idle quota exemption,
    // and the background foreground-service start). A silent revocation changes the whole failure mode,
    // so every recovery records it.
    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    /**
     * Why did the PREVIOUS `:vpn` process die?
     *
     * A silent process death is invisible in our own log — the last line simply stops — and it is the
     * worst failure we have: the kernel closes the tun along with the process, so traffic leaves in the
     * clear while the UI still shows Connected. A dying process cannot report its own death, so the
     * next one reads it from the system here.
     *
     * The reason is the whole diagnosis. CRASH_NATIVE points at libXray/Go; LOW_MEMORY or SIGNALED at
     * the low-memory killer or an OEM policy kill; EXIT_SELF at our own [scheduleProcessDeath].
     */
    private fun logPreviousExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(packageName, /* pid = */ 0, EXIT_REASONS_TO_LOG)
                .filter { it.processName.endsWith(VPN_PROCESS_SUFFIX) }
                .forEach { info ->
                    logd(
                        "previous :vpn exit: pid=${info.pid} reason=${exitReasonLabel(info.reason)} " +
                            "status=${info.status} importance=${info.importance} rssKb=${info.rss} " +
                            "at=${info.timestamp} desc=${info.description}",
                    )
                }
        }.onFailure { logd("previous :vpn exit: unavailable (${it.message})") }
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH_JVM"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN($reason)"
    }

    /**
     * Publish a status to the main process AND remember it, so a main process that starts later can
     * ask what the truth is instead of assuming the tunnel is down.
     *
     * Repeats are dropped. Connected is now asserted by every successful keepalive probe (that is what
     * makes it honest), which without this would be a broadcast every 8 seconds forever.
     */
    private fun publishStatus(status: ConnectionStatus) {
        if (status == lastPublishedStatus) return
        lastPublishedStatus = status
        VpnStatusBroadcast.send(this, status)
    }

    private fun recoveryPrefs() = getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)

    /**
     * Probe the tunnel on a cadence; the probe doubles as a real upstream keepalive. Two consecutive
     * failures (one blip is tolerated) re-enter recovery. Exits when the job is cancelled (disconnect)
     * — delay() is the cancellation point.
     *
     * The loop keeps running with the screen off, just slower. It is best-effort by construction —
     * `delay()` rides CLOCK_MONOTONIC and does not advance while the CPU is suspended — and that is the
     * point: it bounds the dead window at ~[KEEPALIVE_INTERVAL_SCREEN_OFF_MS] of AWAKE time instead of
     * "until the user unlocks".
     */
    private suspend fun keepAliveLoop() {
        var fails = 0
        while (true) {
            delay(if (screenOn) KEEPALIVE_INTERVAL_MS else KEEPALIVE_INTERVAL_SCREEN_OFF_MS)
            if (activeXrayJson == null) return
            val alive = probeTunnel(PROBE_TIMEOUT_MS) ?: continue
            if (alive) {
                fails = 0
                noteTunnelHealthy()
            } else {
                fails++
                logd("keepalive: probe failed ($fails/$KEEPALIVE_FAILS_BEFORE_RECOVER)")
                if (fails >= KEEPALIVE_FAILS_BEFORE_RECOVER) {
                    fails = 0
                    // Same patience as any other trigger. A keepalive failure means the tunnel has gone
                    // quiet, and what un-sticks it is the same QUIC idle timeout regardless of whether
                    // the link visibly moved — so shortening the wait here would only buy an earlier
                    // restart, never an earlier recovery.
                    recover("keepalive")
                }
            }
        }
    }

    /**
     * Real END-TO-END health check: a DNS A-query to [PROBE_DNS_SERVER]:53 that must round-trip through
     * the tunnel. UDP has no local handshake, so — unlike a TCP connect, which xray's tun completes
     * locally — a valid reply can only arrive if traffic genuinely reached the upstream and came back.
     *
     * Three things make the verdict trustworthy, and all three are load-bearing (the previous version
     * had none of them and reported dead tunnels as healthy):
     *  - `connect()` forces the kernel to pick the source address NOW, from the route the datagram will
     *    actually take. Asserting `localAddress == TUN_ADDRESS` is the only way to know the probe went
     *    through the tun rather than escaping onto the physical network.
     *  - the reply's DNS transaction id and QR bit are verified, so a stray datagram can't count as a pass.
     *  - a probe issued inside [PROBE_ESTABLISH_GRACE_MS] of establish() is delayed, not answered: netd
     *    installs the per-UID rules asynchronously, and probes fired in that window egress physically.
     *
     * The socket is intentionally NOT protected — its traffic must traverse the tun (we don't exclude
     * ourselves), which is exactly what we're testing. Literal IP, no DNS resolution of the target.
     */
    private suspend fun probeTunnel(timeoutMs: Int): Boolean? {
        val sinceEstablish = SystemClock.elapsedRealtime() - lastEstablishAt
        if (sinceEstablish < PROBE_ESTABLISH_GRACE_MS) {
            delay(PROBE_ESTABLISH_GRACE_MS - sinceEstablish)
        }
        // null is "we learned nothing", and it is deliberately NOT collapsed into either verdict. The
        // process can be frozen mid-probe by Doze; elapsedRealtime counts through the suspend, so the
        // probe reports a multi-second or multi-minute timeout that measured nothing at all. Scoring
        // that as dead is how an idle phone talked itself into a restart overnight, and scoring it as
        // alive would reset the restart backoff and report Connected on a tunnel we never reached.
        // Retried once first, because the freeze is over by the time we look.
        repeat(PROBE_FROZEN_RETRIES) {
            when (withContext(Dispatchers.IO) { probeTunnelBlocking(timeoutMs) }) {
                ProbeResult.ALIVE -> return true
                ProbeResult.DEAD -> return false
                ProbeResult.INCONCLUSIVE -> Unit
            }
        }
        logd("probe: inconclusive after $PROBE_FROZEN_RETRIES tries — no verdict")
        return null
    }

    private enum class ProbeResult { ALIVE, DEAD, INCONCLUSIVE }

    private fun probeTunnelBlocking(timeoutMs: Int): ProbeResult {
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName(PROBE_DNS_SERVER), 53))
                val local = socket.localAddress?.hostAddress
                if (local != TUN_ADDRESS) {
                    logd("probe: NOT on tun — localAddress=$local expected=$TUN_ADDRESS; verdict=dead")
                    return@use ProbeResult.DEAD
                }
                socket.soTimeout = timeoutMs
                val query = buildDnsQuery(PROBE_DNS_NAME, PROBE_DNS_TXID)
                socket.send(DatagramPacket(query, query.size))
                val response = DatagramPacket(ByteArray(512), 512)
                socket.receive(response) // throws SocketTimeoutException if nothing comes back
                val data = response.data
                val txid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val isReply = (data[2].toInt() and 0x80) != 0
                val ok = response.length >= DNS_HEADER_SIZE && txid == PROBE_DNS_TXID && isReply
                val ms = SystemClock.elapsedRealtime() - started
                if (ok) {
                    logd("probe OK localAddress=$local ${ms}ms")
                } else {
                    logd("probe: bogus reply len=${response.length} txid=0x${txid.toString(16)} qr=$isReply ${ms}ms")
                }
                if (ok) ProbeResult.ALIVE else ProbeResult.DEAD
            }
        }.getOrElse { error ->
            val ms = SystemClock.elapsedRealtime() - started
            // A probe that ran far past its own socket timeout did not measure the tunnel — the thread
            // was suspended mid-probe by Doze or the cached-process freezer, and elapsedRealtime keeps
            // counting through a suspend. Observed at 23708ms against a 600ms deadline, and once at
            // 300069ms. Counting those as evidence of a dead tunnel means a freeze is indistinguishable
            // from a failure, which is how an idle phone talked itself into a restart overnight.
            if (ms > timeoutMs * FROZEN_PROBE_FACTOR) {
                logd("probe: INCONCLUSIVE — ${ms}ms against a ${timeoutMs}ms deadline (process was frozen)")
                return@getOrElse ProbeResult.INCONCLUSIVE
            }
            logd("probe: no answer in ${ms}ms (${error.javaClass.simpleName})")
            ProbeResult.DEAD
        }
    }

    private fun startMonitoring() {
        // Usually screen-on (user-initiated connect), but a crash self-heal can reconnect while in Doze.
        screenOn = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        registerUnderlyingNetworkCallback()
        registerScreenReceiver()
        registerIdleModeReceiver()
        // Probe rather than assume: this is what turns Connecting into Connected, so it has to run now
        // and not after one keepalive interval. On a warm path it answers in ~110ms; on a cold one the
        // ladder's patience carries it, which is the same thing the user would otherwise sit through.
        startTunnelJob("connected", TunnelStart.PROBE_FIRST)
    }

    /**
     * Stop watching the tunnel. The network callback is deliberately NOT unregistered here — it is
     * registered once per process and released only in [onDestroy]. Cycling it per connect leaked
     * registrations (the unregister failure was swallowed) against AOSP's hard cap of 100 concurrent
     * NetworkRequests per UID, which a long-lived `:vpn` process doing in-process recovery can reach.
     */
    private fun stopMonitoring() {
        tunnelJob?.cancel()
        tunnelJob = null
        retryJob?.cancel()
        retryJob = null
        activeXrayJson = null
    }

    private fun unregisterMonitoring() {
        networkCallback?.let { cb -> runCatching { connectivityManager().unregisterNetworkCallback(cb) } }
        networkCallback = null
        underlyingSeeded = false
        lastUnderlying = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        idleModeReceiver?.let { runCatching { unregisterReceiver(it) } }
        idleModeReceiver = null
    }

    // Screen state no longer gates recovery — a handover with the screen off is exactly the case that has
    // to work, and the user must find a live tunnel the instant they unlock. Screen state only sets the
    // keepalive cadence (see [keepAliveLoop]); SCREEN_ON additionally forces an immediate check.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        logd("screen off — keepalive slowing to ${KEEPALIVE_INTERVAL_SCREEN_OFF_MS}ms")
                        screenOn = false
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        logd("screen on — checking tunnel")
                        screenOn = true
                        startTunnelJob("screen on", TunnelStart.PROBE_FIRST)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { registerReceiver(receiver, filter) }
        screenReceiver = receiver
    }

    /**
     * Doze exit — the REAL wake signal, and it fires without the screen ever turning on. SCREEN_ON is
     * only a proxy for it and is kept as a fast path. While dozing, network callbacks are provably
     * missed (the field logs show the underlying network changing with no callback at all), so the
     * first thing this does is re-read the truth from the system rather than trust cached state.
     */
    private fun registerIdleModeReceiver() {
        if (idleModeReceiver != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val idle = runCatching {
                    (getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode
                }.getOrDefault(false)
                logd("device idle mode changed: idle=$idle")
                if (!idle) {
                    refreshUnderlyingFromSystem()
                    startTunnelJob("idle exit", TunnelStart.PROBE_FIRST)
                }
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        runCatching { registerReceiver(receiver, filter) }
        idleModeReceiver = receiver
    }

    /**
     * Pick the best physical network to advertise as our underlying one.
     *
     * `activeNetwork` is useless here because we ARE the VPN and it reports our own tun, so we scan
     * every network for a non-VPN one with INTERNET. The scan must RANK rather than take the first
     * match: `allNetworks` has no documented ordering and empirically comes back netId-ascending, i.e.
     * oldest first. Taking `firstOrNull { validated }` therefore prefers the network being handed OFF
     * over the one being handed to, which is exactly backwards during a Wi-Fi/cellular switch — the
     * field logs show it re-selecting the outgoing cellular network over fresh Wi-Fi every time.
     *
     * Newest-first is the tiebreak that encodes "the network that just appeared is the one the system
     * is moving to". Wi-Fi/Ethernet beat cellular ahead of it because a phone that has both is on
     * Wi-Fi as far as the user is concerned, whatever order the netIds happen to be in.
     */
    private fun scanBestUnderlying(excluding: Network? = null): Network? {
        val cm = connectivityManager()
        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return all.filter { it != excluding }
            .mapNotNull { net -> caps(net)?.takeIf { isUsableUnderlying(it) }?.let { net to it } }
            .maxWithOrNull(
                compareBy(
                    { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) },
                    { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) },
                    { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    { (n, _) -> netIdOf(n) },
                ),
            )?.first
    }

    private fun caps(network: Network): NetworkCapabilities? =
        runCatching { connectivityManager().getNetworkCapabilities(network) }.getOrNull()

    private fun isUsableUnderlying(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    /** `Network` has no public id accessor; its toString is the netId, which rises with age. */
    private fun netIdOf(network: Network): Int =
        network.toString().filter(Char::isDigit).toIntOrNull() ?: 0

    /**
     * Repair [lastUnderlying] if it has silently gone away — and ONLY then.
     *
     * Callbacks are demonstrably dropped across Doze (the field logs show the underlying network having
     * moved with no `onAvailable` ever delivered), so a recovery cannot simply trust cached state. But
     * the previous formulation re-DECIDED instead of repairing: it re-scanned unconditionally and
     * adopted the result whenever it differed, which meant it overruled a correct callback four
     * milliseconds old just as readily as a stale value forty minutes old.
     *
     * That was self-perpetuating, not merely wrong. Writing [lastUnderlying] back to the old network
     * re-arms the `network == lastUnderlying` guard in `onCapabilitiesChanged`, so the next capabilities
     * update for the new network reads as a fresh change and runs another full recovery, which reverts
     * it again — four rounds in seven seconds in the field, ending latched onto the network the user had
     * just left. Since `setUnderlyingNetworks` is what the platform derives the VPN's transports from,
     * that latch is what put a mobile-data indicator in the status bar over a live Wi-Fi connection.
     *
     * So: a live callback wins. We look only for the cached network having vanished.
     */
    private fun refreshUnderlyingFromSystem(): Network? {
        val cached = lastUnderlying
        if (cached != null && caps(cached)?.let { isUsableUnderlying(it) } == true) return cached
        val best = scanBestUnderlying()
        if (best != cached) {
            logd("underlying refresh: cached $cached is gone -> $best")
            adoptUnderlying(best)
        }
        return best
    }

    /**
     * Take [network] as the underlying one and advertise it.
     *
     * [lastLinkSignature] is cleared with it: the signature describes the interface of whichever network
     * was current, so carrying it across a switch makes the next genuine link change on the NEW network
     * compare against the OLD one's interface and fire a spurious recovery.
     */
    private fun adoptUnderlying(network: Network?) {
        lastUnderlying = network
        // Sticky: "have we seeded in this process yet", never "do we currently have a network". Letting
        // a loss clear it would make the NEXT network arrive as a seed instead of a change — and seeding
        // deliberately does not trigger recovery, so a genuine Wi-Fi→cellular switch would go unnoticed
        // until the keepalive happened to catch it. Cleared only by [unregisterMonitoring].
        if (network != null) underlyingSeeded = true
        lastLinkSignature = null
        applyUnderlyingNetworks(network)
    }

    private fun capsLabel(network: Network): String {
        val caps = runCatching { connectivityManager().getNetworkCapabilities(network) }.getOrNull()
            ?: return "caps=?"
        return "${transportLabel(caps)} validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    private fun transportLabel(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
        }
        return "transport=${transports.joinToString("|").ifEmpty { "?" }}"
    }

    private fun connectivityManager(): ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Protect xray's outbound socket from the VPN (route it over the physical underlying network rather
     * than back into our own tun). Called by xray via the DialerController for EVERY outbound socket —
     * so it runs on xray's (Go/JNI) threads and MUST NOT throw: an exception here would cross the JNI
     * boundary back into Go and can crash the `:vpn` process.
     *
     * We deliberately do NOT call `Network.bindSocket()` anymore: it threw EPERM constantly after a
     * Wi-Fi↔cell handover / Doze exit (black-holing the upstream → failed recovery). A protected socket
     * follows the system default network on its own, so a socket created AFTER a handover is already on
     * the new link — which is why recovery is about replacing sockets, not about re-binding them.
     */
    private fun protectSocket(fd: Int): Boolean {
        val protectedOk = runCatching { protect(fd) }.getOrElse {
            logd("protect fd=$fd threw: ${it.message}")
            false
        }
        return protectedOk
    }

    /**
     * Declare which physical network this VPN currently runs over.
     *
     * NB this ROUTES NOTHING. It writes NetworkAgent metadata only — transports, metered/roaming/
     * suspended flags, bandwidth, NetworkStats attribution, and what apps behind the VPN see from
     * `getActiveNetworkInfo()`. It sets no fwmark and touches no routing table: a `protect()`ed socket
     * follows the SYSTEM DEFAULT network, always. We keep it accurate so accounting and the apps behind
     * us are told the truth, not because it repairs anything.
     */
    private fun applyUnderlyingNetworks(network: Network?) {
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
        logd("setUnderlyingNetworks(${network ?: "default"})")
    }

    /**
     * Observe the **physical** underlying network (Wi-Fi/cellular), not the VPN. Contrary to the old
     * assumption, `registerDefaultNetworkCallback` reports the VPN's *own* network here
     * (transport=wifi|vpn, ifc=tun0) — so it never saw real Wi-Fi↔cell changes. A `NOT_VPN + INTERNET`
     * request tracks the actual underlying link (independent of our own routing), so
     * `onAvailable`/`onCapabilitiesChanged(validated)` reflect the physical network and we refresh xray
     * when it changes. Event-driven, no timers.
     */
    private fun registerUnderlyingNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logd("net onAvailable: $network ${capsLabel(network)}")
                // Seeding only. Deliberately NOT a recovery trigger: cellular typically appears
                // unvalidated and validates 1-3s later, so re-dialling here burns the attempt on a link
                // that cannot yet carry the QUIC handshake. onCapabilitiesChanged(VALIDATED) is the
                // real signal.
                if (!underlyingSeeded) {
                    adoptUnderlying(network)
                    logd("underlying seeded: $network")
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                if (network == lastUnderlying) return
                logd("underlying changed (validated): $lastUnderlying -> $network ${transportLabel(caps)}")
                adoptUnderlying(network)
                startTunnelJob("network change", TunnelStart.FORCE_RECOVER)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network != lastUnderlying) return
                // The only signal for a route change that KEEPS the netId — Wi-Fi reassociation, an IPv6
                // prefix change, an inter-RAT handover. None of these fire onAvailable/onCapabilities,
                // so before this they produced no recovery at all. Compare a signature so the routine
                // DNS//MTU churn on a stable link doesn't restart the watcher on every callback.
                val signature = "${lp.interfaceName}|${lp.linkAddresses}|${lp.routes.size}"
                if (signature == lastLinkSignature) return
                // Seed silently. A null signature means we have never seen this network's link before —
                // which is true for every freshly started process, and for the first callback after a
                // switch. Treating that as a CHANGE is what put a brand-new tunnel on trial ~1.1s after
                // establish, condemned it, and killed the process; the replacement process then did the
                // same thing, which is the 8-second restart loop seen in the field on a network that
                // never actually changed. There is nothing to recover from on first sight of a link.
                val seeding = lastLinkSignature == null
                logd("net linkProps ${if (seeding) "seeded" else "changed"} on underlying: $lastLinkSignature -> $signature")
                lastLinkSignature = signature
                if (seeding) return
                startTunnelJob("link properties changed", TunnelStart.PROBE_FIRST)
            }

            override fun onLost(network: Network) {
                logd("net onLost: $network")
                if (network != lastUnderlying) return
                // Advertise the best network we still have rather than blanking to "follow the system
                // default". During a handover there is a window with no default at all, and a VPN agent
                // that inherits no transports shows the user neither a Wi-Fi nor a mobile indicator —
                // the status bar simply goes blank. The replacement link is usually already present
                // (merely not validated yet), and advertising it is closer to the truth than nothing.
                // Excluded explicitly: ConnectivityManager still enumerates a network for a moment after
                // announcing its loss, and still reports usable capabilities for it, so a plain scan
                // hands back the very network we were told is going away ("replaced after loss: 800 ->
                // 800" in the field).
                val replacement = scanBestUnderlying(excluding = network)
                logd("underlying after loss of $network: ${replacement ?: "nothing left"}")
                adoptUnderlying(replacement)
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Single best-matching underlying network — behaves like a default callback.
                connectivityManager().registerBestMatchingNetworkCallback(
                    request,
                    callback,
                    Handler(Looper.getMainLooper()),
                )
            } else {
                connectivityManager().registerNetworkCallback(request, callback)
            }
        }
    }

    /**
     * A recoverable failure: keep everything, wait, try again. Never gives up.
     *
     * In-process on purpose. If xray never started there are no tun readers to strand, so there is
     * nothing a fresh process would buy — and staying alive is what lets a network callback or a
     * screen-on cut the wait short via [retryNow]. The service also stays foreground, so the tunnel
     * comes back without the user touching anything.
     */
    private fun scheduleRetry(reason: String) {
        val prefs = recoveryPrefs()
        val attempt = (prefs.getInt(KEY_RETRY_ATTEMPT, 0) + 1).also {
            runCatching { prefs.edit().putInt(KEY_RETRY_ATTEMPT, it).commit() }
        }
        val wait = RETRY_BACKOFF_MS[(attempt - 1).coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
        logd("retry: attempt=$attempt in ${wait}ms — $reason")
        retryPending = true
        // Report as still working on it, NOT as an error: the user asked for a VPN and we have not
        // stopped trying, so an error would be a lie and would also clear the UI's connect intent.
        publishStatus(ConnectionStatus.Connecting)
        retryJob?.cancel()
        retryJob = scope.launch {
            runCatching { stopTunnel() }
            delay(wait)
            attemptRetry("backoff elapsed")
        }
    }

    /** Cut a pending backoff short — something changed that might make this attempt succeed. */
    private fun retryNow(reason: String) {
        if (!retryPending) return
        logd("retry: $reason — attempting now instead of waiting")
        retryJob?.cancel()
        retryJob = scope.launch { attemptRetry(reason) }
    }

    private suspend fun attemptRetry(reason: String) {
        val config = activeXrayJson ?: paramsStore.load()?.xrayJson
        if (config == null) {
            logd("retry ($reason): nothing to reconnect to — standing down")
            retryPending = false
            return
        }
        logd("retry ($reason): reconnecting")
        runConnect(config)
    }

    /**
     * TERMINAL. Only for failures retrying cannot fix — no configuration at all, an allowlist whose
     * apps are gone, permission revoked. Everything network-shaped goes through [scheduleRetry].
     */
    private fun fail(message: String) {
        scope.launch {
            operationMutex.withLock {
                logd("fail (tearing down): $message")
                retryPending = false
                // Clear persisted config: a fatal failure must NOT crash-restore-crash in a loop.
                paramsStore.clear()
                stopMonitoring()
                stopTunnel()
                publishStatus(ConnectionStatus.Error(message))
                ServiceCompat.stopForeground(this@OnthecrowVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                scheduleProcessDeath()
            }
        }
    }

    // TEMP (diagnosis): all service logs go to the unified, process-tagged, flush-per-line file logger
    // (vpn-debug.log) plus Logcat. Remove this and all logd() call sites once recovery is confirmed.
    private fun logd(message: String) = OtcLog.log(TAG, message)

    private fun startAsForeground() {
        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundServiceType(),
        )
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Onthecrow VPN")
            .setContentText("VPN connection is active")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.onthecrow.onthecrowvpn.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.onthecrow.onthecrowvpn.vpn.DISCONNECT"
        const val ACTION_REVOKE = "com.onthecrow.onthecrowvpn.vpn.REVOKE"
        const val EXTRA_XRAY_JSON = "com.onthecrow.onthecrowvpn.vpn.EXTRA_XRAY_JSON"
        const val EXTRA_DISALLOW = "com.onthecrow.onthecrowvpn.vpn.EXTRA_DISALLOW"
        const val EXTRA_ALLOW = "com.onthecrow.onthecrowvpn.vpn.EXTRA_ALLOW"
        private const val TAG = "OnthecrowVpn"

        // Group 1 is the key and opening quote (kept, so a redaction can splice a placeholder in),
        // group 2 the secret itself (used only for a short fingerprint, never logged whole).
        private val CREDENTIAL_REGEX = Regex("(\"(?:id|password|auth)\"\\s*:\\s*\")([^\"]+)\"")
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001

        // Grace before killing the ":vpn" process on disconnect, so the Disconnected broadcast dispatches.
        private const val PROCESS_DEATH_DELAY_MS = 300L

        private const val RECOVERY_PREFS = "vpn_recovery"
        private const val KEY_LAST_RECOVERY_OK_AT = "last_recovery_ok_at"

        // Restart backoff has to survive the kill, or each fresh process starts clean and hot-loops against a
        // server that is simply down. Reset once a restart is this old, or on any confirmed recovery.
        private const val KEY_RETRY_ATTEMPT = "retry_attempt"
        private const val KEY_LAST_RESTART_AT = "last_restart_at"
        /**
         * Wait before the Nth retry. Uncapped in COUNT — we never stop trying while the user wants the
         * VPN on — but capped in RATE, so a phone in a tunnel or on a plane settles at one attempt per
         * five minutes instead of spinning. Deliberately plain `delay()`, not an alarm: it must not
         * wake a sleeping device. The events that matter (a validated network appearing, idle exit,
         * screen on) short-circuit the wait anyway, so the timer is only the fallback.
         */
        private val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 60_000, 300_000)
        private const val REQ_RESTART = 1

        // Floor under [restartDelayFor]: the process must actually die before the alarm fires, since an
        // alarm delivered into a still-terminating process can be dropped, and a LOST restart is an
        // unbounded leak whereas this delay is a bounded one. Err long, not short.
        private const val MIN_RESTART_DELAY_MS = 500L

        private const val VPN_PROCESS_SUFFIX = ":vpn"
        private const val EXIT_REASONS_TO_LOG = 5

        private const val WAKELOCK_TAG = "OnthecrowVPN:recovery"

        // Safety net only (the wakelock is released in a `finally`), but it has to outlast the ladder or
        // the CPU can suspend mid-recovery: T0's patience alone is 30s, and T1 adds a couple more.
        private const val RECOVERY_WAKELOCK_TIMEOUT_MS = 45_000L

        // How long T0 keeps asking before condemning the tunnel.
        //
        // This number is not a guess about our own code — it is sized to something we now measure
        // directly in the xray log. Nothing we do repairs a tunnel whose path has moved: hysteria2
        // keeps using the QUIC session bound to the vanished interface until quic-go's idle timeout
        // expires, and only the request after that dials again. Two field recoveries, timed from the
        // service log against `proxy/hysteria: connection ends > timeout: no recent network activity`
        // in xray's own output:
        //
        //   Wi-Fi -> cellular, screen off       idle timeout at +13.4s, tunnel back at +18.9s
        //   wake from deep sleep, screen on     idle timeout at +21.6s, tunnel back at +25.4s
        //
        // The second is the expensive one: the idle timer is a Go timer on CLOCK_MONOTONIC, which does
        // not advance while the CPU is suspended, so a path that died during Doze is not noticed until
        // a full timeout has elapsed AWAKE. 25.4s of a 30s budget is not a margin, it is a coin flip,
        // and losing it means a redial and a process restart that could not have helped — the blink is
        // back. xray-core's default MaxIdleTimeout is 30s and it accepts up to 120s, so the bound we
        // are waiting on is genuinely ~30s plus the re-dial.
        //
        // Cheap insurance: this is only ever spent while the tunnel is NOT answering, and a live one
        // still exits on the first probe in ~110ms. Lower it once we set `maxIdleTimeout` ourselves.
        private const val T0_PATIENCE_MS = 45_000L
        private const val T0_PROBE_RETRY_DELAY_MS = 700L

        // Handed to xray so a dead QUIC path is abandoned in ~10s instead of ~30s. [T0_PATIENCE_MS]
        // must stay comfortably above the idle timeout plus the re-dial, or the ladder escalates into a
        // repair while the engine is still on its way to fixing itself.
        private const val QUIC_MAX_IDLE_TIMEOUT_S = 10
        private const val QUIC_KEEPALIVE_PERIOD_S = 3

        // How long T1 gives a fresh dial to come up before handing over to the process restart. A
        // measured cold hysteria2 handshake was ~250ms; this waits out three of them.
        private val T1_PROBE_DELAYS_MS = longArrayOf(500L, 500L, 1_000L)

        // The tun's own address. Shared with the Builder so the probe's "am I actually on the tunnel?"
        // assertion can never drift from what we configured.
        private const val TUN_ADDRESS = "10.77.0.2"

        // Health probe (DNS round-trip through the tunnel). A healthy upstream answers well inside the
        // first step — measured p50 112ms, p90 193ms, and only 0.4% of 2102 successful probes exceeded
        // 500ms — so 600ms is the right question to ask a tunnel that is already up, and it is what the
        // keepalive and the first ask of a trigger use.
        private const val PROBE_TIMEOUT_MS = 600

        // Inside the ladder the timeout GROWS, because there the question is different: not "is this
        // live tunnel still live" but "has this path come up yet". A proxy handshake over a freshly
        // attached LTE bearer has to fit a dial, a QUIC handshake and a DNS round trip into the budget,
        // and 600ms does not cover one lost-and-retransmitted packet, let alone a cold handshake. Every
        // probe failure ever logged was a timeout, never an error, so a short deadline does not detect
        // breakage faster — it only manufactures it.
        private val PROBE_TIMEOUT_STEPS_MS = intArrayOf(600, 1_200, 2_500, 4_000)
        private const val PROBE_DNS_TXID = 0x0C0D
        private const val PROBE_DNS_SERVER = "1.1.1.1"
        private const val PROBE_DNS_NAME = "cloudflare.com"
        private const val DNS_HEADER_SIZE = 12

        // A probe fired within this long of establish() races netd's per-UID rule installation and would
        // egress on the physical network, so it is delayed rather than answered. Measured false-positive
        // probes were 71-135ms after establish; honest ones >=250ms.
        private const val PROBE_ESTABLISH_GRACE_MS = 500L

        // How far past its own deadline a probe must run before we read it as "the process was frozen"
        // rather than "the tunnel is dead". Doze suspends stretch a 600ms probe to seconds or minutes;
        // ordinary jitter does not come close to 4x.
        private const val FROZEN_PROBE_FACTOR = 4
        private const val PROBE_FROZEN_RETRIES = 2

        // Steady-state keepalive while the screen is on: probe this often; this many consecutive failures
        // (one blip tolerated) trigger recovery. The probe doubles as a real upstream keepalive. Tunable.
        private const val KEEPALIVE_INTERVAL_MS = 8_000L

        // Screen off: keep watching, just cheaply. This does not run during CPU suspend at all, so it
        // costs nothing in deep Doze — it only bounds the dead window across awake moments.
        private const val KEEPALIVE_INTERVAL_SCREEN_OFF_MS = 60_000L
        private const val KEEPALIVE_FAILS_BEFORE_RECOVER = 2
    }
}
