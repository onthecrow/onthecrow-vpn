package com.onthecrow.onthecrowvpn.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.VpnService
import android.os.SystemClock
import com.onthecrow.onthecrowvpn.xray.OtcLog
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
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
            if (status is ConnectionStatus.Connected) probeCrossProcessProtect()
        }
        // This process starts out believing the tunnel is down, and it is routinely killed while the
        // tunnel is up — so ask rather than assume. Without this, a relaunch under a live tunnel shows
        // the wrong state and VpnSyncWorker ignores settings changes it should be acting on.
        VpnStatusBroadcast.requestStatus(AndroidVpnEnvironment.applicationContext)
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

    /**
     * TEMP (architecture spike): can this process — which does NOT host the VpnService — protect a
     * socket from the tunnel?
     *
     * The answer decides how xray can be split into its own process. If protect is scoped to the app,
     * xray can call it directly wherever it lives; if it is scoped to the service's process, every
     * outbound socket needs an AIDL round trip to whoever owns the VpnService, which is one call per
     * connection for a TCP-based outbound.
     *
     * Reading the framework says it should work: on API 34/35/36 `VpnService.protect(int)` is a plain
     * delegation to the static `NetworkUtilsInternal.protectFromVpn(int)` and never touches the service
     * instance or its Context, and the method's own documentation scopes failure to "the application
     * ... not prepared or ... revoked". Neither is proof about what netd does with the request, hence
     * this measurement on a real device.
     *
     * Self-calibrating, so the verdict does not depend on a hardcoded tun address: one socket is
     * connected WITHOUT protect to learn what our tunnelled source address is, a second is protected
     * first. Different source addresses mean protect took effect from here.
     *
     * Costs nothing: connect() on a UDP socket only makes the kernel pick a route and a source address.
     * No packet is sent, nothing is read, both sockets are closed immediately.
     */
    private fun probeCrossProcessProtect() {
        if (!protectProbeDone.compareAndSet(false, true)) return
        runCatching {
            val target = InetSocketAddress(InetAddress.getByName(PROTECT_PROBE_TARGET), 53)
            val tunnelled = DatagramSocket().use { it.connect(target); it.localAddress?.hostAddress }
            // An anonymous subclass, because protect() is an instance method that ignores `this`. No
            // Context is attached and none is needed; instantiating it never goes near the framework's
            // service lifecycle.
            val vpn = object : VpnService() {}
            var accepted = false
            val protectedAddr = DatagramSocket().use { socket ->
                accepted = vpn.protect(socket)
                socket.connect(target)
                socket.localAddress?.hostAddress
            }
            val verdict = when {
                !accepted -> "NO — protect() returned false"
                tunnelled == null || protectedAddr == null -> "INCONCLUSIVE — no source address"
                tunnelled == protectedAddr -> "NO — protect() accepted but egress unchanged"
                else -> "YES — egress moved off the tunnel"
            }
            OtcLog.log(
                LOG_TAG,
                "cross-process protect: $verdict (accepted=$accepted tunnelled=$tunnelled protected=$protectedAddr)",
            )
        }.onFailure { OtcLog.log(LOG_TAG, "cross-process protect: probe threw ${it.javaClass.simpleName}: ${it.message}") }
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

    /** One shot per process: the answer cannot change while the process lives. */
    private val protectProbeDone = AtomicBoolean(false)

    private companion object {
        // Literal, so the probe never resolves a name (which would itself go through the tunnel).
        const val PROTECT_PROBE_TARGET = "1.1.1.1"
        const val VPN_PROCESS_SUFFIX = ":vpn"
        const val PROCESS_GONE_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
