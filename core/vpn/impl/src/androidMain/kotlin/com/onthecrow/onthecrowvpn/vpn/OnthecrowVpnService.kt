package com.onthecrow.onthecrowvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onthecrow.onthecrowvpn.xray.AndroidVpnSocketProtector
import com.onthecrow.onthecrowvpn.xray.PlatformXrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import com.onthecrow.onthecrowvpn.xray.XrayRunResult
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
import java.io.FileDescriptor

class OnthecrowVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    // Network monitor: default callback (API 31+, reads the VPN's real underlying network) or a
    // NOT_VPN fallback (< 31). Refreshes xray when the underlying physical network actually changes.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastUnderlying: Network? = null

    @Volatile
    private var underlyingSeeded = false

    private val mtu = 1500
    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private var screenReceiver: BroadcastReceiver? = null

    // The single tunnel-health state machine (recover + keepalive); alive while screen-on + connected.
    private var tunnelJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logd("onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_CONNECT -> {
                startAsForeground()
                val xrayJson = intent.getStringExtra(EXTRA_XRAY_JSON)
                scope.launch { runConnect(xrayJson) }
            }
            ACTION_DISCONNECT -> scope.launch { runDisconnect(stopService = true) }
            // Remote revocation: same teardown as disconnect (Android has no persisted system profile).
            ACTION_REVOKE -> scope.launch { runDisconnect(stopService = true) }
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
                    AndroidVpnSocketProtector.setProtector(::protectAndBind)
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
                        .establish()
                        ?: error("Android refused to establish VPN interface")
                }
                when (val result = startXrayOnTun(configJson)) {
                    XrayRunResult.Success -> {
                        AndroidVpnRuntime.status.value = ConnectionStatus.Connected
                        logd(if (keepTun) "re-dial: connected" else "connect: connected")
                        if (!restart) startMonitoring()
                    }
                    is XrayRunResult.Failure -> handleRunFailure(restart, result.message)
                }
            }.onFailure { error ->
                handleRunFailure(restart, error.message ?: "Failed to start VPN")
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
        // TEMP (diagnosis): verbose xray log into a pullable file. Revert logLevel/errorLogPath when fixed.
        val xrayLogPath = runCatching { File(getExternalFilesDir(null), "xray.log").absolutePath }.getOrNull()
        val runtimeJson = sanitizer.withTunInbound(
            configJson,
            mtu = mtu,
            logLevel = "info",
            errorLogPath = xrayLogPath,
        )
        return xrayEngine.start(runtimeJson)
    }

    /** Stop xray and close the dup tun fd it was using (we own it). The tun interface stays up. */
    private suspend fun stopXray() {
        xrayEngine.stop()
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
            AndroidVpnRuntime.status.value = ConnectionStatus.Disconnecting
            stopMonitoring()
            stopTunnel()
            AndroidVpnRuntime.status.value = ConnectionStatus.Disconnected
            if (stopService) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Full teardown: stop xray AND close the tun interface (used on disconnect / fatal error). */
    private suspend fun stopTunnel() {
        stopXray()
        AndroidVpnSocketProtector.setProtector(null)
        tunInterface?.let { runCatching { it.close() } }
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
        if (activeXrayJson == null) return
        tunnelJob?.cancel()
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
     * Escalating full re-establish until an end-to-end probe confirms health, or the backoff is
     * exhausted. The first backoff entry doubles as the settle (let the radio/route commit). Each attempt
     * re-queries the physical network ([refreshUnderlyingFromSystem]) since Doze can leave [lastUnderlying]
     * stale. On exhaustion we DON'T give up — we fall through to keepalive, which re-triggers recovery.
     */
    private suspend fun recoverWithBackoff(reason: String) {
        for ((attempt, backoffMs) in RECOVERY_BACKOFF_MS.withIndex()) {
            delay(backoffMs)
            if (activeXrayJson == null) return
            refreshUnderlyingFromSystem()
            logd("recover ($reason) attempt ${attempt + 1}/${RECOVERY_BACKOFF_MS.size}: underlying=$lastUnderlying")
            runConnect(xrayJson = null, restart = true, forceFullReconnect = true)
            if (awaitTunnelHealthy(HEALTH_WINDOW_MS)) {
                logd("recover ($reason): tunnel healthy — done")
                return
            }
            logd("recover ($reason) attempt ${attempt + 1}: not healthy")
        }
        logd("recover ($reason): backoff exhausted — keepalive keeps watching")
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
     * Real END-TO-END health check: connect, send a tiny HTTP request, and require a response byte back.
     * A plain connect() is NOT enough — xray's tun stack completes the TCP handshake LOCALLY (observed:
     * "probe OK after 2ms", impossible over a real radio), so a successful connect only proves xray's
     * inbound is alive, not that the upstream relays. Reading a byte that can only come from 1.1.1.1
     * proves traffic genuinely flows through the upstream. Plain HTTP on a literal IP — no DNS, not the
     * xray server (the client stays server-agnostic); the tunnel egress hides it from carrier port-80
     * interception.
     */
    private fun probeTunnel(timeoutMs: Int): Boolean {
        return runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("1.1.1.1", 80), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().apply {
                    write("GET / HTTP/1.1\r\nHost: 1.1.1.1\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().read() >= 0
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
        registerUnderlyingNetworkCallback()
        registerScreenReceiver()
        // We just connected & it's healthy; begin keepalive watching (screen is on at connect time).
        startTunnelJob("connected", TunnelStart.KEEPALIVE_ONLY)
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
                        tunnelJob?.cancel()
                        tunnelJob = null
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        logd("screen on — checking tunnel")
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
     * Protect xray's server socket from the VPN AND bind it to the current physical network. `protect`
     * alone leaves the socket on the system default, which for **UDP** (connectionless) stays stale
     * after a Wi-Fi↔cell handover — so TCP traffic recovered but QUIC/UDP (YouTube/Instagram) didn't.
     * `Network.bindSocket(fd)` pins both TCP and UDP sockets to the active link. Called by xray via the
     * DialerController for every outbound socket.
     */
    private fun protectAndBind(fd: Int): Boolean {
        val protectedOk = protect(fd)
        lastUnderlying?.let { network ->
            runCatching {
                // Wrap the raw fd in a FileDescriptor (without taking ownership) so we can bind it.
                val javaFd = FileDescriptor()
                FileDescriptor::class.java.getDeclaredField("descriptor").apply { isAccessible = true }
                    .setInt(javaFd, fd)
                network.bindSocket(javaFd)
            }
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
                    startTunnelJob("network change", TunnelStart.FORCE_RECOVER)
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
                stopMonitoring()
                stopTunnel()
                AndroidVpnRuntime.status.value = ConnectionStatus.Error(message)
                ServiceCompat.stopForeground(this@OnthecrowVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // TEMP (diagnosis): mirror logs to a pullable file so field Doze/network tests are inspectable
    // without adb. Remove (along with the file + format) once recovery is confirmed.
    private val debugLogFile: File? by lazy {
        runCatching { File(getExternalFilesDir(null), "vpn-debug.log") }.getOrNull()
    }
    private val logTimeFormat by lazy {
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    }

    private fun logd(message: String) {
        if (isDebuggable) Log.d(TAG, message)
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screen = if (pm.isInteractive) "on" else "off"
            val ts = logTimeFormat.format(java.util.Date(System.currentTimeMillis()))
            debugLogFile?.appendText("$ts [doze=${pm.isDeviceIdleMode} screen=$screen] $message\n")
        }
    }

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
        private const val TAG = "OnthecrowVpn"
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001

        // Wait before each recovery attempt (also the inter-attempt backoff). The first entry doubles as
        // the settle (let the radio/route commit). On exhaustion keepalive keeps watching. Tunable.
        private val RECOVERY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L)

        // How long to keep probing the tunnel after a re-establish before deciding the attempt failed
        // and escalating. A healthy upstream answers <1s; a wedged one hangs. Each probe blocks up to
        // PROBE_TIMEOUT_MS. Tunable.
        private const val HEALTH_WINDOW_MS = 4_000L
        private const val PROBE_TIMEOUT_MS = 2_000
        private const val PROBE_GAP_MS = 500L

        // Steady-state keepalive while the screen is on: probe this often; this many consecutive failures
        // (one blip tolerated) re-enters recovery. The probe doubles as a real upstream keepalive. Tunable.
        private const val KEEPALIVE_INTERVAL_MS = 10_000L
        private const val KEEPALIVE_FAILS_BEFORE_RECOVER = 2
    }
}
