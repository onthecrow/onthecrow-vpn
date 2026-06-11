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

    // elapsedRealtime when the screen last went off (0 = unknown/seen-on). Used to decide whether a
    // wake warrants a proactive re-dial (Doze / long screen-off kills the upstream session).
    @Volatile
    private var screenOffAtMs = 0L

    private val mtu = 1500
    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private var screenReceiver: BroadcastReceiver? = null

    // Delayed full reconnect after a network change / Doze wake (see scheduleFullReconnect).
    private var reconnectJob: Job? = null

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

    // ---- Resilience: refresh xray (never touch routing) when the underlying network changes ----

    /**
     * Recover the tunnel after a physical-network change (Wi-Fi↔cell) or a Doze wake. We wait for the
     * radio/route to settle, then [refreshUnderlyingFromSystem] (re-query the live physical network —
     * after Doze the frozen network callback can leave [lastUnderlying] pointing at a STALE Network
     * object) and re-dial. Coalesced: a rapid second trigger cancels and reschedules.
     *
     * [soft] = keep the tun interface and only restart xray (rebinding its sockets to the fresh
     * network). EXPERIMENT (Doze): testing whether re-querying the network is enough to make the cheap
     * soft re-dial recover, so we can avoid the heavier full teardown+establish. [soft]=false does a
     * full re-establish (the confirmed-working path for network changes).
     */
    private fun scheduleReconnect(reason: String, soft: Boolean) {
        if (activeXrayJson == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Progressive verified retry: a fixed settle is a guess (sometimes the network/route isn't
            // ready yet and the re-establish lands on dead routing). Instead, after each re-establish we
            // wait for Android to validate the VPN network (its own probe through the tunnel — the only
            // honest "traffic actually flows" signal, since we're excluded from our own VPN and can't
            // probe it ourselves). If it doesn't validate, retry with a longer settle. Stop as soon as
            // it's healthy so we never tear down a working tunnel.
            for ((attempt, settleMs) in RECONNECT_BACKOFF_MS.withIndex()) {
                delay(settleMs)
                if (activeXrayJson == null) return@launch
                refreshUnderlyingFromSystem()
                logd("reconnect ($reason) attempt ${attempt + 1}/${RECONNECT_BACKOFF_MS.size}: soft=$soft underlying=$lastUnderlying")
                runConnect(xrayJson = null, restart = true, forceFullReconnect = !soft)
                if (awaitTunnelHealthy(HEALTH_WINDOW_MS)) {
                    logd("reconnect ($reason): tunnel healthy — done")
                    return@launch
                }
                logd("reconnect ($reason): not healthy in ${HEALTH_WINDOW_MS}ms — will retry")
            }
            logd("reconnect ($reason): retries exhausted, leaving tunnel for next event")
        }
    }

    /**
     * Suspend until a real probe THROUGH the tunnel succeeds or [windowMs] elapses. We can't trust
     * NET_CAPABILITY_VALIDATED — Android reports it optimistically/instantly for VPNs (observed: "validated
     * after 1ms" while no traffic actually flowed). So we open a TCP socket bound to the VPN network and
     * try to reach a public IP; success means xray's upstream is genuinely relaying.
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
     * Real end-to-end health check: a plain TCP connect to a public IP. Since we no longer exclude
     * ourselves from the VPN, our default route IS the tunnel, so this traverses it (xray relays it) —
     * success means the upstream genuinely flows. 1.1.1.1:443 is a generic connectivity target — no DNS,
     * not the xray server (the client stays server-agnostic).
     */
    private fun probeTunnel(timeoutMs: Int): Boolean {
        return runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("1.1.1.1", 443), timeoutMs)
                true
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
    }

    private fun stopMonitoring() {
        reconnectJob?.cancel()
        reconnectJob = null
        networkCallback?.let { cb -> runCatching { connectivityManager().unregisterNetworkCallback(cb) } }
        networkCallback = null
        underlyingSeeded = false
        lastUnderlying = null
        activeXrayJson = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    // On wake, proactively re-dial: Doze / a long screen-off kills the upstream (e.g. hysteria2's QUIC
    // session), and xray only notices it ~30s later via its idle timeout — that's the "no internet for
    // up to a minute after unlocking" window. Forcing a tun + xray refresh here re-dials a fresh session
    // immediately (~1-2s). Gated so a quick screen toggle (session still alive) stays a no-op.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOffAtMs = SystemClock.elapsedRealtime()
                        logd("screen off")
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> maybeRefreshOnWake(intent.action)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(receiver, filter) }
        screenReceiver = receiver
    }

    private fun maybeRefreshOnWake(action: String?) {
        if (activeXrayJson == null) return
        val offForMs = if (screenOffAtMs == 0L) 0L else SystemClock.elapsedRealtime() - screenOffAtMs
        val wasDoze = (getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode
        val shouldRefresh = wasDoze || offForMs >= WAKE_REFRESH_THRESHOLD_MS
        logd("wake ($action): offFor=${offForMs}ms doze=$wasDoze -> refresh=$shouldRefresh")
        if (shouldRefresh) {
            screenOffAtMs = 0L // debounce: only one refresh per off→on cycle (SCREEN_ON + USER_PRESENT)
            // Doze needs a FULL reconnect: a soft re-dial (keep tun, restart xray) does NOT recover —
            // the kept tun interface goes stale through Doze and only a fresh establish() restores the
            // data path (proven: soft re-dial reported "connected" but no traffic; manual reconnect /
            // full re-establish recovers). Network was NOT stale, so it's the tun, not the socket.
            scheduleReconnect("wake", soft = false)
        }
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
                    scheduleReconnect("network change", soft = false)
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

        // Below this screen-off duration the upstream session is usually still alive, so a wake is a
        // no-op (Doze is handled separately via isDeviceIdleMode). Tunable.
        private const val WAKE_REFRESH_THRESHOLD_MS = 30_000L

        // Progressive settle before each reconnect attempt (also the retry backoff): give the OS time
        // to commit to the new/woken network. Each attempt that doesn't validate falls through to the
        // next, longer wait. Tunable.
        private val RECONNECT_BACKOFF_MS = longArrayOf(3_000L, 6_000L, 12_000L)

        // How long to keep probing the tunnel after a re-establish before deciding the attempt failed
        // and retrying. Each probe blocks up to PROBE_TIMEOUT_MS. Tunable.
        private const val HEALTH_WINDOW_MS = 6_000L
        private const val PROBE_TIMEOUT_MS = 2_000
        private const val PROBE_GAP_MS = 500L
    }
}
