package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.NetworkExtension.NETunnelProviderManager
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.NetworkExtension.NEVPNConnection
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusConnected
import platform.NetworkExtension.NEVPNStatusConnecting
import platform.NetworkExtension.NEVPNStatusDisconnecting
import platform.NetworkExtension.NEVPNStatusReasserting
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume

/**
 * iOS VPN controller. The single source of truth is the system: the NE packet-tunnel runs in a
 * separate process that outlives the app, so we never keep our own optimistic state as truth.
 * Instead we observe `NEVPNStatusDidChangeNotification` from construction and reconcile with any
 * profile already installed/connected (e.g. left running by a previous app launch). `connect` and
 * `disconnect` only *ask* the system to change; the resulting status always flows back via the
 * observer.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformVpnController : VpnController {
    private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = mutableStatus

    private val sanitizer = XrayConfigSanitizer()
    private var manager: NETunnelProviderManager? = null

    @Suppress("unused")
    private val statusObserver: NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(
            // object = null: observe every VPN connection so we reflect the system even for a
            // tunnel we did not start in this process lifetime.
            name = "NEVPNStatusDidChangeNotification",
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? ->
            manager?.let { mutableStatus.value = mapStatus(it.connection.status) }
        }

    init {
        reconcile()
    }

    /** Adopt an existing system profile and publish its real status at launch. */
    private fun reconcile() {
        NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, _ ->
            val existing = managers?.firstOrNull() as? NETunnelProviderManager
            manager = existing
            mutableStatus.value =
                existing?.let { mapStatus(it.connection.status) } ?: ConnectionStatus.Disconnected
        }
    }

    override suspend fun connect(xrayJson: String): ConnectResult {
        return try {
            val runtimeJson = sanitizer.withTunInbound(xrayJson, name = "tun0", mtu = 1500, logLevel = "warning")
            val serverHost = extractServerHost(runtimeJson) ?: "onthecrow.vpn"

            val mgr = loadOrCreateManager()
            val proto = NETunnelProviderProtocol().apply {
                providerBundleIdentifier = extensionBundleId()
                serverAddress = serverHost
                providerConfiguration = mapOf<Any?, Any?>("xrayJson" to runtimeJson)
            }
            mgr.setProtocolConfiguration(proto)
            mgr.setLocalizedDescription("Onthecrow VPN")
            mgr.setEnabled(true)

            saveAndReload(mgr)
            manager = mgr

            val started = startTunnel(mgr.connection)
            if (started == null) {
                // Status now driven by the observer (Connecting → Connected).
                ConnectResult.Started
            } else {
                // Reflect the system's real status rather than a stale optimistic value.
                mutableStatus.value = mapStatus(mgr.connection.status)
                ConnectResult.Failed(started)
            }
        } catch (t: Throwable) {
            val message = t.message ?: "Failed to start VPN"
            mutableStatus.value = manager?.let { mapStatus(it.connection.status) }
                ?: ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    override suspend fun disconnect() {
        // Ask the system to stop; the observer publishes Disconnecting → Disconnected.
        manager?.connection?.stopVPNTunnel()
            ?: run { mutableStatus.value = ConnectionStatus.Disconnected }
    }

    private suspend fun loadOrCreateManager(): NETunnelProviderManager =
        suspendCancellableCoroutine { cont ->
            NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, _ ->
                val existing = (managers?.firstOrNull() as? NETunnelProviderManager)
                cont.resume(existing ?: NETunnelProviderManager())
            }
        }

    private suspend fun saveAndReload(mgr: NETunnelProviderManager) {
        suspendCancellableCoroutine { cont ->
            mgr.saveToPreferencesWithCompletionHandler { saveError ->
                if (saveError != null) {
                    cont.resume(Unit) // surfaced later via status; tolerate "permission needs grant"
                } else {
                    mgr.loadFromPreferencesWithCompletionHandler { cont.resume(Unit) }
                }
            }
        }
    }

    private fun startTunnel(connection: NEVPNConnection): String? = memScoped {
        val errVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = connection.startVPNTunnelAndReturnError(errVar.ptr)
        if (ok) null else (errVar.value?.localizedDescription ?: "Could not start the VPN tunnel")
    }

    private fun mapStatus(status: NEVPNStatus): ConnectionStatus = when (status) {
        NEVPNStatusConnected -> ConnectionStatus.Connected
        NEVPNStatusConnecting, NEVPNStatusReasserting -> ConnectionStatus.Connecting
        NEVPNStatusDisconnecting -> ConnectionStatus.Disconnecting
        else -> ConnectionStatus.Disconnected
    }

    // Derived from the running app's bundle id so it matches both the .dev (debug)
    // and release configurations automatically — the extension must be a child id.
    private fun extensionBundleId(): String =
        (NSBundle.mainBundle.bundleIdentifier ?: "com.onthecrow.onthecrowvpn.OnthecrowVPN") + ".PacketTunnel"

    private fun extractServerHost(xrayJson: String): String? {
        val idx = xrayJson.indexOf("\"address\"")
        if (idx < 0) return null
        val colon = xrayJson.indexOf(':', idx)
        val q1 = xrayJson.indexOf('"', colon + 1)
        val q2 = xrayJson.indexOf('"', q1 + 1)
        if (q1 < 0 || q2 < 0) return null
        return xrayJson.substring(q1 + 1, q2).takeIf {
            it.isNotBlank() && it != "127.0.0.1" && it != "0.0.0.0" && it != "::1"
        }
    }
}
