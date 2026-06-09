package com.onthecrow.onthecrowvpn.vpn

import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.StateFlow

actual class PlatformVpnController : VpnController {
    override val status: StateFlow<ConnectionStatus> = AndroidVpnRuntime.status

    override suspend fun connect(xrayJson: String): ConnectResult {
        return runCatching {
            val context = AndroidVpnEnvironment.applicationContext
            AndroidVpnRuntime.status.value = ConnectionStatus.Connecting
            val intent = Intent(context, OnthecrowVpnService::class.java)
                .setAction(OnthecrowVpnService.ACTION_CONNECT)
                .putExtra(OnthecrowVpnService.EXTRA_XRAY_JSON, xrayJson)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            ConnectResult.Started
        }.getOrElse { error ->
            val message = error.message ?: "Failed to start VPN service"
            AndroidVpnRuntime.status.value = ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    override suspend fun disconnect() = sendStop(OnthecrowVpnService.ACTION_DISCONNECT)

    override suspend fun revoke() = sendStop(OnthecrowVpnService.ACTION_REVOKE)

    private fun sendStop(action: String) {
        runCatching {
            val context = AndroidVpnEnvironment.applicationContext
            AndroidVpnRuntime.status.value = ConnectionStatus.Disconnecting
            context.startService(
                Intent(context, OnthecrowVpnService::class.java).setAction(action)
            )
        }.onFailure { error ->
            AndroidVpnRuntime.status.value = ConnectionStatus.Error(
                error.message ?: "Failed to stop VPN service",
            )
        }
    }
}
