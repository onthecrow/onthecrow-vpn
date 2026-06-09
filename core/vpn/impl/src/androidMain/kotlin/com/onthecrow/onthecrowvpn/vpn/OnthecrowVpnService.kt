package com.onthecrow.onthecrowvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onthecrow.onthecrowvpn.xray.AndroidVpnSocketProtector
import com.onthecrow.onthecrowvpn.xray.PlatformXrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import com.onthecrow.onthecrowvpn.xray.XrayRunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OnthecrowVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val xrayEngine = PlatformXrayEngine()
    private val sanitizer = XrayConfigSanitizer()
    private val operationMutex = Mutex()
    private var tunFd: Int? = null

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        scope.launch { runDisconnect(stopService = true) }
    }

    override fun onDestroy() {
        runBlocking { operationMutex.withLock { stopMonitoring(); stopTunnel() } }
        scope.cancel()
        super.onDestroy()
    }

    // restart=true is a transparent refresh (underlying-network change / wake from Doze): the VPN
    // session and the Connected status are kept (no flicker, no permission re-prompt); only the tun +
    // xray are rebuilt so xray dials fresh sockets over the current underlying network. A refresh
    // failure is non-fatal — the next network event recovers it.
    private suspend fun runConnect(xrayJson: String?, restart: Boolean = false) {
        operationMutex.withLock {
            val configJson = xrayJson ?: activeXrayJson
            if (configJson.isNullOrBlank()) {
                fail("No validated configuration is available")
                return
            }
            logd(if (restart) "refresh: rebuilding tunnel" else "connect: establishing tunnel")
            runCatching {
                stopTunnel()
                AndroidVpnSocketProtector.setProtector(::protect)
                val vpnInterface = Builder()
                    .setSession("Onthecrow VPN")
                    .setMtu(mtu)
                    .addAddress("10.77.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    // Exclude ourselves from the tunnel so this process's default network is the real
                    // physical network — lets us observe the underlying network change (Wi-Fi↔LTE) via
                    // registerDefaultNetworkCallback. Our own sockets are protected anyway.
                    .apply { runCatching { addDisallowedApplication(packageName) } }
                    .establish()
                    ?: error("Android refused to establish VPN interface")
                val fd = vpnInterface.detachFd()
                tunFd = fd
                activeXrayJson = configJson
                // NOTE: deliberately NOT calling setUnderlyingNetworks. Android auto-follows the
                // default network (Wi-Fi↔cellular) on its own; pinning it here previously broke
                // fallback. We only *observe* the underlying network to refresh xray's sockets.
                val runtimeJson = sanitizer.withTunInbound(
                    configJson,
                    mtu = mtu,
                    logLevel = if (isDebuggable) "info" else "none",
                )
                xrayEngine.setTunFd(fd)
                when (val result = xrayEngine.start(runtimeJson)) {
                    XrayRunResult.Success -> {
                        AndroidVpnRuntime.status.value = ConnectionStatus.Connected
                        logd(if (restart) "refresh: connected" else "connect: connected")
                        if (!restart) startMonitoring()
                    }
                    is XrayRunResult.Failure -> handleRunFailure(restart, result.message)
                }
            }.onFailure { error ->
                handleRunFailure(restart, error.message ?: "Failed to start VPN")
            }
        }
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

    private suspend fun stopTunnel() {
        xrayEngine.stop()
        AndroidVpnSocketProtector.setProtector(null)
        tunFd?.let { fd ->
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
        tunFd = null
    }

    // ---- Resilience: refresh xray (never touch routing) when the underlying network changes ----

    /** Rebuild the tun + xray on the current underlying network. Serialized via operationMutex. */
    private fun requestRefresh(reason: String) {
        if (activeXrayJson == null) return
        logd("refresh requested ($reason)")
        scope.launch { runConnect(xrayJson = null, restart = true) }
    }

    private fun startMonitoring() {
        underlyingSeeded = false
        lastUnderlying = null
        registerUnderlyingNetworkCallback()
    }

    private fun stopMonitoring() {
        networkCallback?.let { cb -> runCatching { connectivityManager().unregisterNetworkCallback(cb) } }
        networkCallback = null
        underlyingSeeded = false
        lastUnderlying = null
        activeXrayJson = null
    }

    private fun connectivityManager(): ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Because this app is excluded from its own tunnel (addDisallowedApplication above), the default
     * network of *this process* is the real physical network the VPN runs over — not the VPN. So the
     * default-network callback reports the underlying network directly, and fires exactly when the OS
     * switches the default to a new, ready network (Wi-Fi→LTE), however long that takes. Event-driven,
     * no timers. On a change we refresh xray so it re-dials over the new network.
     */
    private fun registerUnderlyingNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!underlyingSeeded) {
                    underlyingSeeded = true
                    lastUnderlying = network
                    logd("underlying seeded: $network")
                    return
                }
                if (network != lastUnderlying) {
                    logd("underlying changed: $lastUnderlying -> $network")
                    lastUnderlying = network
                    requestRefresh("underlying changed")
                }
            }

            override fun onLost(network: Network) {
                if (network == lastUnderlying) {
                    logd("underlying lost: $network")
                    lastUnderlying = null
                }
            }
        }
        networkCallback = callback
        runCatching { connectivityManager().registerDefaultNetworkCallback(callback) }
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

    private fun logd(message: String) {
        if (isDebuggable) Log.d(TAG, message)
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
    }
}
