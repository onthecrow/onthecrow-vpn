package com.onthecrow.onthecrowvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

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

    // The tun interface, kept OPEN for the whole session so the virtual interface (and the OS routing
    // of app traffic into it) survives Wi-Fi↔cell handovers — we never rebuild it on a network change,
    // only re-dial xray. xray gets a *dup* of this fd ([xrayTunFd]) so it can be stopped/restarted
    // without tearing the interface down.
    private var tunInterface: ParcelFileDescriptor? = null
    private var xrayTunFd: Int? = null

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

    // Live screen state. A network change while the screen is OFF (Doze) does NOT trigger heavy recovery
    // (battery); we only refresh the underlying network and let the next SCREEN_ON probe+recover.
    @Volatile
    private var screenOn = true

    // The single tunnel-health state machine (recover + keepalive); alive while screen-on + connected.
    private var tunnelJob: Job? = null

    // Persisted connect params so this :vpn process can self-reconnect after a crash / system kill.
    private val paramsStore by lazy { ConnectionParamsStore(this) }

    override fun onCreate() {
        super.onCreate()
        // We run in the ":vpn" process, where the Application does NOT bring up the app graph — set up
        // the small env libXray needs (datDir) ourselves.
        AndroidXrayEnvironment.initialize(this)
        AndroidVpnEnvironment.initialize(this)
        // TEMP (diagnosis): route any common-code logs running in this process into the same file.
        DebugLog.setSink { tag, message -> OtcLog.log(tag, message) }
        logd("onCreate (:vpn process) sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logd("onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_CONNECT -> {
                startAsForeground()
                val xrayJson = intent.getStringExtra(EXTRA_XRAY_JSON)
                activeDisallow = intent.getStringArrayListExtra(EXTRA_DISALLOW).orEmpty()
                activeAllow = intent.getStringArrayListExtra(EXTRA_ALLOW).orEmpty()
                // Persist for crash self-heal (cleared on deliberate teardown / fatal failure).
                if (!xrayJson.isNullOrBlank()) {
                    paramsStore.save(ConnectionParams(xrayJson, activeDisallow, activeAllow))
                }
                scope.launch { runConnect(xrayJson) }
            }
            ACTION_DISCONNECT -> scope.launch { runDisconnect(stopService = true) }
            // Remote revocation: same teardown as disconnect (Android has no persisted system profile).
            ACTION_REVOKE -> scope.launch { runDisconnect(stopService = true) }
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

    // Called by the system when the VPN is torn down externally (user revokes it from system
    // settings/Quick Settings, or another app's VPN takes over). Mirror that instead of leaving a
    // stale "Connected" status.
    override fun onRevoke() {
        logd("onRevoke (external teardown)")
        scope.launch { runDisconnect(stopService = true) }
    }

    override fun onDestroy() {
        logd("onDestroy")
        runBlocking { operationMutex.withLock { stopMonitoring(); stopTunnel() } }
        scope.cancel()
        super.onDestroy()
    }

    // restart=true is a transparent refresh (underlying-network change / wake from Doze): the VPN
    // session and the Connected status are kept (no flicker, no permission re-prompt). Crucially we do
    // NOT rebuild the tun interface — only re-dial xray over the new network. Rebuilding the tun via
    // establish() during a Wi-Fi↔cell handover raced and left app traffic black-holed (no packets
    // reached the new tun). Keeping the same tun keeps the OS app-routing stable; only xray's upstream
    // sockets move. A refresh failure is non-fatal — the next network event recovers it.
    private suspend fun runConnect(
        xrayJson: String?,
        restart: Boolean = false,
        forceFullReconnect: Boolean = false,
    ) {
        operationMutex.withLock {
            val configJson = xrayJson ?: activeXrayJson
            logd(
                "runConnect: restart=$restart forceFull=$forceFullReconnect " +
                    "hasConfig=${!configJson.isNullOrBlank()} tunUp=${tunInterface != null} underlying=$lastUnderlying",
            )
            if (configJson.isNullOrBlank()) {
                fail("No validated configuration is available")
                return
            }
            activeXrayJson = configJson
            // Keep the VPN pinned to the current physical network so xray's protected sockets follow a
            // Wi-Fi↔cell handover (dynamic — never pins to a dead link).
            applyUnderlyingNetworks(lastUnderlying)

            // keepTun = soft re-dial. Currently every refresh (network change / Doze wake) passes
            // forceFullReconnect=true, since only a full teardown + re-establish recovers (see
            // scheduleFullReconnect); the soft path is kept for potential same-network quick refreshes.
            val keepTun = restart && tunInterface != null && !forceFullReconnect
            logd(if (keepTun) "re-dial: keep tun, restart xray only" else "connect: establishing tunnel")
            runCatching {
                if (keepTun) {
                    stopXray()
                } else {
                    stopTunnel()
                    AndroidVpnSocketProtector.setProtector(::protectSocket)
                    tunInterface = Builder()
                        .setSession("Onthecrow VPN")
                        .setMtu(mtu)
                        .addAddress("10.77.0.2", 32)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("1.1.1.1")
                        // NB: we deliberately do NOT exclude ourselves from the tunnel — our own traffic
                        // routes through it so the health probe ([probeTunnel]) actually tests the tunnel.
                        // No loop risk: xray's upstream sockets are protected individually via protectFd.
                        // The underlying physical network is observed via the NOT_VPN NetworkRequest
                        // callback (independent of our process routing), not via our default network.
                        .apply { applySplitTunnel(this) }
                        .establish()
                        ?: error("Android refused to establish VPN interface")
                    logd("tun established: fd=${tunInterface?.fd}")
                }
                // freshSession = a user-initiated connect (not a re-dial/recovery, which keep restart=true)
                // → reset the xray.log only then, so all recovery attempts stay in one pullable file.
                when (val result = startXrayOnTun(configJson, freshSession = !restart)) {
                    XrayRunResult.Success -> {
                        VpnStatusBroadcast.send(this, ConnectionStatus.Connected)
                        logd(if (keepTun) "re-dial: connected" else "connect: connected")
                        if (!restart) startMonitoring()
                    }
                    is XrayRunResult.Failure -> handleRunFailure(restart, result.message)
                }
            }.onFailure { error ->
                // Don't swallow cancellation: if this coroutine (e.g. a recovery attempt) was cancelled
                // mid-establish, propagate it instead of mislabelling it a "refresh failure" and pressing on.
                if (error is CancellationException) {
                    logd("runConnect CANCELLED (restart=$restart): ${error.message}")
                    throw error
                }
                logd("runConnect FAILED (restart=$restart): ${error.stackTraceToString()}")
                handleRunFailure(restart, error.message ?: "Failed to start VPN")
            }
        }
    }

    /**
     * Give xray a fresh **dup** of the held-open tun fd and start it. We dup so xray can be
     * stopped/restarted (on every re-dial) without ever closing the master [tunInterface] — the
     * virtual interface, and the OS routing of app traffic into it, stay up across the handover.
     */
    private suspend fun startXrayOnTun(configJson: String, freshSession: Boolean): XrayRunResult {
        val master = tunInterface ?: return XrayRunResult.Failure("Tun interface is not established")
        val fd = master.dup().detachFd()
        xrayTunFd = fd
        xrayEngine.setTunFd(fd)
        // TEMP (diagnosis): verbose xray log into a pullable file. logLevel=debug surfaces the upstream
        // dial / hysteria QUIC reconnect chatter we need. Revert logLevel/errorLogPath when fixed.
        val xrayLogPath = prepareXrayLogFile(reset = freshSession)
        val runtimeJson = sanitizer.withTunInbound(
            configJson,
            mtu = mtu,
            logLevel = "debug",
            errorLogPath = xrayLogPath,
        )
        // TEMP (diagnosis): fingerprint of the client credential actually handed to xray — verifies a
        // config switch really reaches the engine (per-client id/password/auth differ; prefix only).
        val cred = Regex("\"(?:id|password|auth)\"\\s*:\\s*\"([^\"]+)\"")
            .find(runtimeJson)?.groupValues?.get(1)
        logd("xray start: tunFd=$fd client-credential=${cred?.take(6) ?: "?"}… (xray.log loglevel=debug)")
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
    private fun prepareXrayLogFile(reset: Boolean): String? = runCatching {
        File(getExternalFilesDir(null), "xray.log").apply {
            if (reset) delete()
            if (!exists()) {
                parentFile?.mkdirs()
                createNewFile()
            }
            // World-readable so adb shell / file managers can copy it (harmless if FUSE ignores chmod —
            // app-ownership from createNewFile() is what actually makes it pullable).
            runCatching { setReadable(true, false) }
            logd("xray.log prepared: reset=$reset path=$absolutePath readable=${canRead()}")
        }.absolutePath
    }.getOrElse {
        logd("xray.log prepare FAILED: ${it.message}")
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
            activeAllow.isNotEmpty() -> (activeAllow + packageName).distinct().forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { logd("split-tunnel: cannot allow $pkg: ${it.message}") }
            }
        }
        if (activeDisallow.isNotEmpty() || activeAllow.isNotEmpty()) {
            logd("split-tunnel: disallow=$activeDisallow allow=$activeAllow")
        }
    }

    /** Stop xray and close the dup tun fd it was using (we own it). The tun interface stays up. */
    private suspend fun stopXray() {
        val result = xrayEngine.stop()
        logd("xray stop: $result (closing dupFd=$xrayTunFd)")
        xrayTunFd?.let { fd -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() } }
        xrayTunFd = null
    }

    private fun handleRunFailure(restart: Boolean, message: String) {
        if (restart) {
            // Transient refresh failure (e.g. a mid-switch moment): keep the VPN session alive; the
            // next underlying-network change or screen-on will recover it. Never tear down here.
            logd("refresh failed, keeping VPN alive: $message")
        } else {
            fail(message)
        }
    }

    private suspend fun runDisconnect(stopService: Boolean) {
        operationMutex.withLock {
            logd("runDisconnect: stopService=$stopService")
            // Deliberate teardown: forget the persisted config so a later sticky restart does NOT
            // resurrect a tunnel the user (or a revocation) intentionally stopped.
            paramsStore.clear()
            VpnStatusBroadcast.send(this, ConnectionStatus.Disconnecting)
            stopMonitoring()
            stopTunnel()
            VpnStatusBroadcast.send(this, ConnectionStatus.Disconnected)
            if (stopService) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                // Kill this ":vpn" process so xray-core's process-global hysteria connection pool is
                // gone — the next connect starts a clean process and never reuses a stale QUIC session.
                // Delay so the Disconnected broadcast above is dispatched first. stopSelf() already
                // marked us stopped, so START_STICKY will NOT auto-restart this killed process.
                scheduleProcessDeath()
            }
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

    // ---- Resilience: one state machine guards tunnel health while the screen is on ----
    //
    // Both triggers (Doze/screen wake AND physical-network change) feed ONE job. It (optionally) recovers
    // via escalating full re-establishes verified by a real end-to-end probe, then settles into a
    // keepalive loop that re-enters recovery on silent death — so while the screen is on the tunnel is
    // never left dead, and we don't guess (offFor/doze) — we probe the actual tunnel. Cancelled on
    // screen-off / disconnect (no probing in Doze — battery; the next screen-on re-checks).

    private enum class TunnelStart {
        /** Just connected & known healthy — go straight to keepalive (don't re-probe immediately). */
        KEEPALIVE_ONLY,

        /** Screen on / wake: probe first; recover only if the tunnel is actually dead. */
        PROBE_FIRST,

        /** Physical-network change: the old upstream socket is dead — recover straight away. */
        FORCE_RECOVER,
    }

    private fun startTunnelJob(reason: String, start: TunnelStart) {
        if (activeXrayJson == null) {
            logd("startTunnelJob($reason/$start) ignored — no active config")
            return
        }
        if (tunnelJob?.isActive == true) logd("startTunnelJob($reason): cancelling in-flight tunnel job")
        tunnelJob?.cancel()
        logd("startTunnelJob: reason=$reason start=$start")
        tunnelJob = scope.launch {
            when (start) {
                TunnelStart.FORCE_RECOVER -> recoverWithBackoff(reason)
                TunnelStart.PROBE_FIRST -> {
                    if (probeTunnel(PROBE_TIMEOUT_MS)) {
                        logd("$reason: tunnel already healthy")
                    } else {
                        logd("$reason: tunnel dead on check — recovering")
                        recoverWithBackoff(reason)
                    }
                }
                TunnelStart.KEEPALIVE_ONLY -> logd("$reason: starting keepalive watch")
            }
            keepAliveLoop()
        }
    }

    /**
     * Escalating full re-establish until an end-to-end probe confirms health. Attempt 0 fires
     * IMMEDIATELY (no pre-delay) — on a good network the first re-dial already succeeds in ~1-3s;
     * later attempts back off ([recoveryDelayMs]: 1s/2s/4s) to let the radio/route settle. Each attempt
     * re-queries the physical network ([refreshUnderlyingFromSystem]) since Doze can leave [lastUnderlying]
     * stale. After [MAX_FAST_ATTEMPTS] we DON'T give up — we fall through to keepalive, which re-triggers
     * recovery on the next probe failure.
     */
    private suspend fun recoverWithBackoff(reason: String) {
        var attempt = 0
        while (attempt < MAX_FAST_ATTEMPTS) {
            if (activeXrayJson == null) return
            val delayMs = recoveryDelayMs(attempt)
            if (delayMs > 0) delay(delayMs)
            if (activeXrayJson == null) return
            refreshUnderlyingFromSystem()
            attempt++
            logd("recover ($reason) attempt $attempt/$MAX_FAST_ATTEMPTS: underlying=$lastUnderlying")
            runConnect(xrayJson = null, restart = true, forceFullReconnect = true)
            if (awaitTunnelHealthy(HEALTH_WINDOW_MS)) {
                logd("recover ($reason): tunnel healthy — done")
                return
            }
            logd("recover ($reason) attempt $attempt: not healthy")
        }
        logd("recover ($reason): fast attempts exhausted — keepalive keeps watching")
    }

    /**
     * While the screen is on, probe the tunnel every [KEEPALIVE_INTERVAL_MS]; the probe doubles as a real
     * upstream keepalive. Two consecutive failures (one blip is tolerated) re-enter recovery. Exits when
     * the job is cancelled (screen-off / disconnect) — delay() is the cancellation point.
     */
    private suspend fun keepAliveLoop() {
        var fails = 0
        while (true) {
            delay(KEEPALIVE_INTERVAL_MS)
            if (activeXrayJson == null) return
            if (probeTunnel(PROBE_TIMEOUT_MS)) {
                fails = 0
            } else {
                fails++
                logd("keepalive: probe failed ($fails/$KEEPALIVE_FAILS_BEFORE_RECOVER)")
                if (fails >= KEEPALIVE_FAILS_BEFORE_RECOVER) {
                    fails = 0
                    recoverWithBackoff("keepalive")
                }
            }
        }
    }

    /**
     * Suspend until a real end-to-end probe through the tunnel succeeds ([probeTunnel]) or [windowMs]
     * elapses. A healthy upstream answers in <1s; a wedged one hangs — so this distinguishes a working
     * re-establish from a dead one without trusting NET_CAPABILITY_VALIDATED (which Android reports
     * optimistically for VPNs — observed "validated after 1ms" while no traffic flowed).
     */
    private suspend fun awaitTunnelHealthy(windowMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + windowMs
        var probes = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            probes++
            if (probeTunnel(PROBE_TIMEOUT_MS)) {
                logd("tunnel probe OK after ${SystemClock.elapsedRealtime() - start}ms ($probes probes)")
                return true
            }
            delay(PROBE_GAP_MS)
        }
        logd("tunnel probe FAILED ($probes probes / ${windowMs}ms)")
        return false
    }

    /**
     * Real END-TO-END health check via a DNS query over the tunnel: send a UDP DNS A-query to
     * 1.1.1.1:53 and require ANY datagram back. UDP has no local handshake (so — unlike a TCP connect,
     * which xray's tun completes locally — a reply can ONLY arrive if traffic genuinely round-tripped
     * through the upstream). It also avoids the old false-negative: the previous TCP-to-1.1.1.1:80 probe
     * reported "dead" whenever Cloudflare RST'd plain port 80, even though the round-trip had worked.
     *
     * The socket is intentionally NOT protected — its traffic must traverse the tun (we don't exclude
     * ourselves), which is exactly what we're testing. Literal IP, no DNS resolution of the target.
     */
    private fun probeTunnel(timeoutMs: Int): Boolean {
        return runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val query = buildDnsQuery("cloudflare.com", PROBE_DNS_TXID)
                val target = java.net.InetSocketAddress(java.net.InetAddress.getByName("1.1.1.1"), 53)
                socket.send(java.net.DatagramPacket(query, query.size, target))
                val response = java.net.DatagramPacket(ByteArray(512), 512)
                socket.receive(response) // throws SocketTimeoutException if nothing comes back
                response.length > 0
            }
        }.getOrElse { false }
    }

    /**
     * Re-query the live physical (NOT_VPN) network straight from the system and update [lastUnderlying]
     * if it went stale. This is the core of the Doze hypothesis: during Doze the network callback is
     * frozen, so if the cellular radio re-attaches as a *new* Network we never get the [onAvailable] and
     * keep binding sockets to a dead handle. We scan allNetworks for the physical link, because now that
     * we route through the VPN, activeNetwork reports the VPN — not the underlying network.
     */
    private fun refreshUnderlyingFromSystem() {
        val fresh = queryActiveUnderlying()
        when {
            fresh == null ->
                logd("underlying refresh: no active NOT_VPN network (keeping $lastUnderlying)")
            fresh != lastUnderlying -> {
                logd("underlying refresh: STALE $lastUnderlying -> $fresh")
                lastUnderlying = fresh
                applyUnderlyingNetworks(fresh)
            }
            else -> logd("underlying refresh: unchanged ($lastUnderlying)")
        }
    }

    /** The current physical (NOT_VPN + INTERNET) network, preferring a validated one. */
    private fun queryActiveUnderlying(): Network? {
        val cm = connectivityManager()
        val networks = runCatching { cm.allNetworks }.getOrNull() ?: return null
        var fallback: Network? = null
        for (n in networks) {
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n
            if (fallback == null) fallback = n
        }
        return fallback
    }

    private fun startMonitoring() {
        underlyingSeeded = false
        lastUnderlying = null
        // Usually screen-on (user-initiated connect), but a crash self-heal can reconnect while in Doze.
        screenOn = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        registerUnderlyingNetworkCallback()
        registerScreenReceiver()
        // Begin keepalive watching only while the screen is on (battery); SCREEN_ON resumes it otherwise.
        if (screenOn) {
            startTunnelJob("connected", TunnelStart.KEEPALIVE_ONLY)
        } else {
            logd("connected while screen off — keepalive deferred to screen-on")
        }
    }

    private fun stopMonitoring() {
        tunnelJob?.cancel()
        tunnelJob = null
        networkCallback?.let { cb -> runCatching { connectivityManager().unregisterNetworkCallback(cb) } }
        networkCallback = null
        underlyingSeeded = false
        lastUnderlying = null
        activeXrayJson = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    // The tunnel job lives only while the screen is on: SCREEN_ON (re)starts it (probe-first — recover if
    // Doze/idle killed the upstream), SCREEN_OFF cancels it (no probing in Doze — battery). A quick toggle
    // is a no-op because the first probe just passes. No offFor/doze heuristic — we check the real tunnel.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        logd("screen off — pausing tunnel watcher")
                        screenOn = false
                        tunnelJob?.cancel()
                        tunnelJob = null
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
     * Wi-Fi↔cell handover / Doze exit (black-holing the upstream → failed recovery). Following the live
     * network is handled by `protect()` + [applyUnderlyingNetworks] (setUnderlyingNetworks) together with
     * the FULL xray restart every recovery does — fresh protected sockets use the current default network.
     */
    private fun protectSocket(fd: Int): Boolean {
        val protectedOk = runCatching { protect(fd) }.getOrElse {
            logd("protect fd=$fd threw: ${it.message}")
            false
        }
        return protectedOk
    }

    /**
     * Tell the system which physical network the VPN currently runs over, so protected sockets follow
     * it across a Wi-Fi↔cell handover. `null` falls back to the system default. Updated dynamically on
     * every network change, so it never pins to a dead link.
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
                if (!underlyingSeeded) {
                    underlyingSeeded = true
                    lastUnderlying = network
                    applyUnderlyingNetworks(network)
                    logd("underlying seeded: $network")
                    return
                }
                if (network != lastUnderlying) {
                    logd("underlying changed: $lastUnderlying -> $network")
                    lastUnderlying = network
                    applyUnderlyingNetworks(network)
                    // Screen on: recover now. Screen off (Doze): just keep the underlying network fresh
                    // and defer heavy recovery to the next SCREEN_ON (battery) — PROBE_FIRST will catch it.
                    if (screenOn) {
                        startTunnelJob("network change", TunnelStart.FORCE_RECOVER)
                    } else {
                        logd("network change while screen off — deferring recovery to screen-on")
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                logd("net linkProps: $network ifc=${lp.interfaceName}")
            }

            override fun onLost(network: Network) {
                logd("net onLost: $network")
                if (network == lastUnderlying) {
                    lastUnderlying = null
                }
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

    private fun fail(message: String) {
        scope.launch {
            operationMutex.withLock {
                logd("fail (tearing down): $message")
                // Clear persisted config: a fatal failure must NOT crash-restore-crash in a loop.
                paramsStore.clear()
                stopMonitoring()
                stopTunnel()
                VpnStatusBroadcast.send(this@OnthecrowVpnService, ConnectionStatus.Error(message))
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
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001

        // Grace before killing the ":vpn" process on disconnect, so the Disconnected broadcast dispatches.
        private const val PROCESS_DEATH_DELAY_MS = 300L

        // Per-attempt recovery cadence lives in commonMain (recoveryDelayMs / MAX_FAST_ATTEMPTS) so it's
        // unit-testable: attempt 0 fires immediately, then 1s/2s/4s.

        // How long to keep probing the tunnel after a re-establish before deciding the attempt failed
        // and escalating. A healthy upstream (DNS) answers <1s; a wedged one hangs. Each probe blocks up
        // to PROBE_TIMEOUT_MS. Tunable.
        private const val HEALTH_WINDOW_MS = 3_000L
        private const val PROBE_TIMEOUT_MS = 1_500
        private const val PROBE_GAP_MS = 500L
        private const val PROBE_DNS_TXID = 0x0C0D

        // Steady-state keepalive while the screen is on: probe this often; this many consecutive failures
        // (one blip tolerated) re-enters recovery. The probe doubles as a real upstream keepalive. Tunable.
        private const val KEEPALIVE_INTERVAL_MS = 8_000L
        private const val KEEPALIVE_FAILS_BEFORE_RECOVER = 2
    }
}
