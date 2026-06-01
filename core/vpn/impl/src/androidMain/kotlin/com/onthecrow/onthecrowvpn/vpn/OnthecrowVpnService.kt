package com.onthecrow.onthecrowvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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

class OnthecrowVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val xrayEngine = PlatformXrayEngine()
    private val sanitizer = XrayConfigSanitizer()
    private var tunFd: Int? = null
    private val mtu = 1500
    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startAsForeground()
                scope.launch { connect() }
            }
            ACTION_DISCONNECT -> scope.launch { disconnect(stopService = true) }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        runBlocking { stopTunnel() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connect() {
        val config = AndroidVpnRuntime.pendingConfig
        if (config == null) {
            fail("No validated configuration is available")
            return
        }

        runCatching {
            stopTunnel()
            AndroidVpnSocketProtector.setProtector(::protect)
            val vpnInterface = Builder()
                .setSession("Onthecrow VPN")
                .setMtu(mtu)
                .addAddress("10.77.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish()
                ?: error("Android refused to establish VPN interface")
            val fd = vpnInterface.detachFd()
            tunFd = fd
            val runtimeJson = sanitizer.withTunInbound(
                config.xrayJson,
                mtu = mtu,
                logLevel = if (isDebuggable) "info" else "none",
            )
            xrayEngine.setTunFd(fd)
            when (val result = xrayEngine.start(runtimeJson)) {
                XrayRunResult.Success -> AndroidVpnRuntime.status.value = ConnectionStatus.Connected
                is XrayRunResult.Failure -> fail(result.message)
            }
        }.onFailure { error ->
            fail(error.message ?: "Failed to start VPN")
        }
    }

    private suspend fun disconnect(stopService: Boolean) {
        AndroidVpnRuntime.status.value = ConnectionStatus.Disconnecting
        stopTunnel()
        AndroidVpnRuntime.status.value = ConnectionStatus.Disconnected
        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    private fun fail(message: String) {
        scope.launch {
            stopTunnel()
            AndroidVpnRuntime.status.value = ConnectionStatus.Error(message)
            ServiceCompat.stopForeground(this@OnthecrowVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001
    }
}
