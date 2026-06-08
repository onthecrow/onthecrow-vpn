package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSUserDefaults
import platform.NetworkExtension.NETunnelProviderManager
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.NetworkExtension.NEVPNConnection
import platform.NetworkExtension.NEVPNStatus
import kotlin.coroutines.resume

/**
 * Shared NetworkExtension management for Apple platforms — the part that is identical on iOS and
 * macOS. It owns one `NETunnelProviderManager` (the system VPN profile) and knows how to install,
 * start, stop, observe it, and read the failure reason the packet-tunnel extension publishes via the
 * shared App Group.
 *
 * It is intentionally a *plain* class (no Koin, no StateFlow, no run loop) so it can be reused by:
 *  - the iOS [PlatformVpnController] (in-app, on the UIKit main run loop), and
 *  - the macOS bridge executable (a CLI inside the app bundle, on its own NSRunLoop).
 *
 * The only platform-varying input is [providerBundleId]: on iOS it is the app-extension id
 * (`<app>.PacketTunnel`); on macOS it is the installed system-extension's bundle id. The caller
 * supplies it.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleTunnelManager(
    private val providerBundleId: String,
    private val appGroupId: String = DEFAULT_APP_GROUP_ID,
    private val localizedDescription: String = "Onthecrow VPN",
) {
    private val sanitizer = XrayConfigSanitizer()
    private var manager: NETunnelProviderManager? = null

    /** Adopt an already-installed profile (if any) and return its live connection status. */
    suspend fun loadExisting(): NEVPNStatus? = suspendCancellableCoroutine { cont ->
        NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, _ ->
            val existing = managers?.firstOrNull() as? NETunnelProviderManager
            manager = existing
            cont.resume(existing?.connection?.status)
        }
    }

    /** Live status of the adopted manager, or null if none is installed/adopted yet. */
    fun currentStatus(): NEVPNStatus? = manager?.connection?.status

    /**
     * Install/refresh the profile from [xrayJson] and start the tunnel. [beforeStart] runs in the
     * tiny window right before `startVPNTunnel` (the caller uses it to flip its "pending connect"
     * flag so the status poller can't misread the pre-start Disconnected as a failure).
     *
     * @return null if the tunnel was asked to start, or an error message on synchronous failure.
     */
    suspend fun connect(
        xrayJson: String,
        beforeStart: () -> Unit = {},
        onWarning: (String) -> Unit = {},
        debugLogPath: String? = null,
    ): String? {
        val runtimeJson = sanitizer.withTunInbound(
            xrayJson,
            name = "tun0",
            mtu = 1500,
            logLevel = if (debugLogPath != null) "debug" else "warning",
            errorLogPath = debugLogPath,
        )
        val serverHost = extractServerHost(runtimeJson) ?: "onthecrow.vpn"

        val mgr = loadOrCreateManager()
        val proto = NETunnelProviderProtocol().apply {
            providerBundleIdentifier = providerBundleId
            serverAddress = serverHost
            providerConfiguration = mapOf<Any?, Any?>("xrayJson" to runtimeJson)
        }
        mgr.setProtocolConfiguration(proto)
        mgr.setLocalizedDescription(localizedDescription)
        mgr.setEnabled(true)

        saveAndReload(mgr, onWarning)
        manager = mgr
        clearSharedError()
        beforeStart()
        return startTunnel(mgr.connection)
    }

    /** Stop the tunnel. Returns true if there was a manager to stop. */
    fun disconnect(): Boolean {
        val mgr = manager ?: return false
        mgr.connection.stopVPNTunnel()
        return true
    }

    /** Read the failure reason the extension wrote to the shared App Group store, if any. */
    fun readSharedError(): String? =
        NSUserDefaults(suiteName = appGroupId).stringForKey(ERROR_KEY)?.takeIf { it.isNotBlank() }

    fun clearSharedError() {
        NSUserDefaults(suiteName = appGroupId).removeObjectForKey(ERROR_KEY)
    }

    private suspend fun loadOrCreateManager(): NETunnelProviderManager =
        suspendCancellableCoroutine { cont ->
            NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, _ ->
                val existing = managers?.firstOrNull() as? NETunnelProviderManager
                cont.resume(existing ?: NETunnelProviderManager())
            }
        }

    private suspend fun saveAndReload(mgr: NETunnelProviderManager, onWarning: (String) -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            mgr.saveToPreferencesWithCompletionHandler { saveError ->
                if (saveError != null) {
                    // Tolerate (iOS shows a first-time permission prompt), but surface the reason so a
                    // headless/desktop caller can tell a real failure (e.g. missing entitlement) apart.
                    onWarning("saveToPreferences failed: ${saveError.localizedDescription}")
                    cont.resume(Unit)
                } else {
                    mgr.loadFromPreferencesWithCompletionHandler { cont.resume(Unit) }
                }
            }
        }
    }

    @OptIn(BetaInteropApi::class)
    private fun startTunnel(connection: NEVPNConnection): String? = memScoped {
        val errVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = connection.startVPNTunnelAndReturnError(errVar.ptr)
        if (ok) null else (errVar.value?.localizedDescription ?: "Could not start the VPN tunnel")
    }

    private fun extractServerHost(xrayJson: String): String? {
        val idx = xrayJson.indexOf("\"address\"")
        if (idx < 0) return null
        val colon = xrayJson.indexOf(':', idx)
        val q1 = xrayJson.indexOf('"', colon + 1)
        val q2 = xrayJson.indexOf('"', q1 + 1)
        if (q1 < 0 || q2 < 0) return null
        return xrayJson.substring(q1 + 1, q2).takeIf {
            it.isNotBlank() && it != "127.0.0.1" && it != "0.0.0.0" && it != "::1" &&
                // Only a clean host/IP token — anything else (spaces, CIDRs, rule prefixes) is not a
                // valid NETunnelNetworkSettings.tunnelRemoteAddress and would reject the tunnel.
                it.all { c -> c.isLetterOrDigit() || c == '.' || c == ':' || c == '-' || c == '_' }
        }
    }

    companion object {
        const val DEFAULT_APP_GROUP_ID = "group.com.onthecrow.onthecrowvpn"
        private const val ERROR_KEY = "lastTunnelError"
    }
}
