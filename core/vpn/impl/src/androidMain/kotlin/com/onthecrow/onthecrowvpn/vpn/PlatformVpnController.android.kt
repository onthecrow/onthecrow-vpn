package com.onthecrow.onthecrowvpn.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.onthecrow.onthecrowvpn.xray.OtcLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private const val LOG_TAG = "CTRL"

actual class PlatformVpnController : VpnController {
    override val status: StateFlow<ConnectionStatus> = AndroidVpnRuntime.status

    init {
        // The service lives in the ":vpn" process now, so its status arrives via broadcast; mirror it
        // into the main-process StateFlow the rest of the app already observes.
        //
        // This process takes NO part in recovery. It is normally dead while the tunnel runs, so the
        // `:vpn` process owns its own recovery ladder end to end; we are a UI mirror.
        VpnStatusBroadcast.register(AndroidVpnEnvironment.applicationContext) { status ->
            OtcLog.log(LOG_TAG, "status broadcast received: $status")
            AndroidVpnRuntime.status.value = status
        }
    }

    override suspend fun connect(xrayJson: String): ConnectResult {
        return runCatching {
            val context = AndroidVpnEnvironment.applicationContext
            OtcLog.log(LOG_TAG, "connect: requested (jsonBytes=${xrayJson.length})")
            AndroidVpnRuntime.status.value = ConnectionStatus.Connecting
            // Make sure any previous ":vpn" process has fully died before starting a new tunnel, so its
            // xray-core global hysteria pool is gone and we don't reconnect onto a stale QUIC session.
            awaitVpnProcessGone(context, PROCESS_GONE_TIMEOUT_MS)
            OtcLog.log(
                LOG_TAG,
                "connect: starting service disallow=${AndroidSplitTunnelState.disallow} allow=${AndroidSplitTunnelState.allow}",
            )
            val intent = Intent(context, OnthecrowVpnService::class.java)
                .setAction(OnthecrowVpnService.ACTION_CONNECT)
                .putExtra(OnthecrowVpnService.EXTRA_XRAY_JSON, xrayJson)
                // Resolved per-app routing (kept current by SplitTunnelAndroidSync); applied in the
                // :vpn process's VpnService.Builder. At most one list is non-empty.
                .putStringArrayListExtra(
                    OnthecrowVpnService.EXTRA_DISALLOW,
                    ArrayList(AndroidSplitTunnelState.disallow),
                )
                .putStringArrayListExtra(
                    OnthecrowVpnService.EXTRA_ALLOW,
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
            AndroidVpnRuntime.status.value = ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    override suspend fun disconnect() = sendStop(OnthecrowVpnService.ACTION_DISCONNECT)

    override suspend fun revoke() = sendStop(OnthecrowVpnService.ACTION_REVOKE)

    private fun sendStop(action: String) {
        OtcLog.log(LOG_TAG, "sendStop: $action")
        runCatching {
            val context = AndroidVpnEnvironment.applicationContext
            AndroidVpnRuntime.status.value = ConnectionStatus.Disconnecting
            context.startService(
                Intent(context, OnthecrowVpnService::class.java).setAction(action)
            )
        }.onFailure { error ->
            OtcLog.log(LOG_TAG, "sendStop FAILED: ${error.message}")
            AndroidVpnRuntime.status.value = ConnectionStatus.Error(
                error.message ?: "Failed to stop VPN service",
            )
        }
    }

    /** Poll until no ":vpn" process is alive (our own processes are always visible to us). */
    private suspend fun awaitVpnProcessGone(context: Context, timeoutMs: Long) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val start = SystemClock.elapsedRealtime()
        val deadline = start + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val alive = runCatching { am.runningAppProcesses }.getOrNull()
                ?.any { it.processName.endsWith(VPN_PROCESS_SUFFIX) } ?: false
            if (!alive) {
                OtcLog.log(LOG_TAG, "awaitVpnProcessGone: clear after ${SystemClock.elapsedRealtime() - start}ms")
                return
            }
            delay(POLL_INTERVAL_MS)
        }
        OtcLog.log(LOG_TAG, "awaitVpnProcessGone: TIMED OUT after ${timeoutMs}ms — :vpn still alive")
    }

    private companion object {
        const val VPN_PROCESS_SUFFIX = ":vpn"
        const val PROCESS_GONE_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
