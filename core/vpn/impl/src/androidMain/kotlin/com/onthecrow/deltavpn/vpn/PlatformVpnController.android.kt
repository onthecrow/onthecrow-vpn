package com.onthecrow.deltavpn.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import com.onthecrow.deltavpn.errorreporting.ErrorDomain
import com.onthecrow.deltavpn.errorreporting.ErrorReporter
import com.onthecrow.deltavpn.xray.OtcLog
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val LOG_TAG = "CTRL"

actual class PlatformVpnController : VpnController, KoinComponent {
    override val status: StateFlow<ConnectionStatus> = AndroidVpnRuntime.status
    private val errorReporter: ErrorReporter by inject()

    // No init block, and nothing to mirror: the service writes [AndroidVpnRuntime.status] directly,
    // because it now runs in this very process. The broadcast round trip that used to carry it — and
    // the second one that let a freshly started UI ask what the truth was — are both gone with it.

    override suspend fun connect(xrayJson: String): ConnectResult {
        return runCatching {
            val context = AndroidVpnEnvironment.applicationContext
            OtcLog.log(LOG_TAG, "connect: requested (jsonBytes=${xrayJson.length})")
            AndroidVpnRuntime.status.value = ConnectionStatus.Connecting
            // The "wait for the old process to die first" step used to live here, so a reconnect could
            // not land on xray's still-warm hysteria pool. The invariant survives; its home moved. The
            // service's own `runConnect` begins with `stopTunnel()`, which now kills the `:xray`
            // process outright — so the pool is provably gone before the new engine is asked for, and
            // it no longer depends on this process polling ActivityManager and giving up on a timeout.
            OtcLog.log(
                LOG_TAG,
                "connect: starting service disallow=${AndroidSplitTunnelState.disallow} allow=${AndroidSplitTunnelState.allow}",
            )
            val intent = Intent(context, DeltaVpnService::class.java)
                .setAction(DeltaVpnService.ACTION_CONNECT)
                .putExtra(DeltaVpnService.EXTRA_XRAY_JSON, xrayJson)
                // Resolved per-app routing (kept current by SplitTunnelAndroidSync); applied in the
                // service's VpnService.Builder. At most one list is non-empty.
                .putStringArrayListExtra(
                    DeltaVpnService.EXTRA_DISALLOW,
                    ArrayList(AndroidSplitTunnelState.disallow),
                )
                .putStringArrayListExtra(
                    DeltaVpnService.EXTRA_ALLOW,
                    ArrayList(AndroidSplitTunnelState.allow),
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            OtcLog.log(LOG_TAG, "connect: service start requested")
            ConnectResult.Started
        }.getOrElse { error ->
            val message = error.message ?: "Failed to start VPN service"
            OtcLog.log(LOG_TAG, "connect: FAILED $message")
            errorReporter.report(ErrorDomain.VPN_TUNNEL, error)
            AndroidVpnRuntime.status.value = ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    override suspend fun disconnect() = sendStop(DeltaVpnService.ACTION_DISCONNECT)

    override suspend fun revoke() = sendStop(DeltaVpnService.ACTION_REVOKE)

    private fun sendStop(action: String) {
        OtcLog.log(LOG_TAG, "sendStop: $action")
        runCatching {
            val context = AndroidVpnEnvironment.applicationContext
            AndroidVpnRuntime.status.value = ConnectionStatus.Disconnecting
            context.startService(
                Intent(context, DeltaVpnService::class.java).setAction(action)
            )
        }.onFailure { error ->
            OtcLog.log(LOG_TAG, "sendStop FAILED: ${error.message}")
            errorReporter.report(ErrorDomain.VPN_TUNNEL, error)
            AndroidVpnRuntime.status.value = ConnectionStatus.Error(
                error.message ?: "Failed to stop VPN service",
            )
        }
    }

}
