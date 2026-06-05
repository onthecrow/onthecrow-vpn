package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSUserDefaults
import platform.NetworkExtension.NETunnelProviderManager
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.NetworkExtension.NEVPNConnection
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusConnected
import platform.NetworkExtension.NEVPNStatusConnecting
import platform.NetworkExtension.NEVPNStatusDisconnecting
import platform.NetworkExtension.NEVPNStatusReasserting
import kotlin.coroutines.resume

/**
 * iOS VPN controller. The single source of truth is the system: the NE packet-tunnel runs in a
 * separate process that outlives the app, so we never keep optimistic state as truth.
 *
 * Status is published by polling the live `NEVPNConnection.status` of the installed profile on the
 * main run loop. We deliberately do NOT rely on `NEVPNStatusDidChangeNotification` — its block
 * callback proved unreliable in this Kotlin/Native setup (it never fired during a session, so the
 * UI only updated on the next user interaction). Polling reads the real OS status, so the button
 * reflects connect/disconnect (including external changes from Settings) within one tick, and the
 * launch `reconcile()` gives the correct state immediately on cold start.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformVpnController : VpnController {
    private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = mutableStatus

    private val sanitizer = XrayConfigSanitizer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: NETunnelProviderManager? = null

    // True between asking the system to connect and reaching Connected. If the OS instead drops
    // back to Disconnected while this is set, the tunnel failed — we then surface the reason the
    // extension wrote to the shared App Group store (or a generic message).
    private var pendingConnect = false

    init {
        reconcile()
        scope.launch {
            while (isActive) {
                // StateFlow dedups, so this only emits when the OS status actually changes.
                manager?.let { publish(mapStatus(it.connection.status)) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Publish a polled status, turning a failed connection attempt into an informative Error. */
    private fun publish(mapped: ConnectionStatus) {
        when (mapped) {
            ConnectionStatus.Connected -> {
                pendingConnect = false
                mutableStatus.value = mapped
            }
            ConnectionStatus.Disconnected -> {
                if (pendingConnect) {
                    pendingConnect = false
                    mutableStatus.value = ConnectionStatus.Error(readSharedError() ?: GENERIC_FAILURE)
                } else {
                    mutableStatus.value = mapped
                }
            }
            else -> mutableStatus.value = mapped
        }
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
            clearSharedError()
            pendingConnect = true

            val started = startTunnel(mgr.connection)
            if (started == null) {
                // Async outcome now driven by the poller: Connecting → Connected, or a drop back
                // to Disconnected which publish() turns into an Error with the extension's reason.
                ConnectResult.Started
            } else {
                // Synchronous failure — the caller surfaces this message itself.
                pendingConnect = false
                mutableStatus.value = mapStatus(mgr.connection.status)
                ConnectResult.Failed(started)
            }
        } catch (t: Throwable) {
            pendingConnect = false
            val message = t.message ?: "Failed to start VPN"
            mutableStatus.value = manager?.let { mapStatus(it.connection.status) }
                ?: ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    override suspend fun disconnect() {
        // User-initiated stop is not a failure.
        pendingConnect = false
        manager?.connection?.stopVPNTunnel()
            ?: run { mutableStatus.value = ConnectionStatus.Disconnected }
    }

    private fun sharedDefaults(): NSUserDefaults? = NSUserDefaults(suiteName = APP_GROUP_ID)

    /** Read the failure reason the extension wrote to the shared App Group store, if any. */
    private fun readSharedError(): String? =
        sharedDefaults()?.stringForKey(ERROR_KEY)?.takeIf { it.isNotBlank() }

    private fun clearSharedError() {
        sharedDefaults()?.removeObjectForKey(ERROR_KEY)
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
            it.isNotBlank() && it != "127.0.0.1" && it != "0.0.0.0" && it != "::1" &&
                // Only a clean host/IP token — anything else (spaces, CIDRs, rule prefixes) is not a
                // valid NETunnelNetworkSettings.tunnelRemoteAddress and would reject the tunnel.
                it.all { c -> c.isLetterOrDigit() || c == '.' || c == ':' || c == '-' || c == '_' }
        }
    }

    private companion object {
        private const val POLL_INTERVAL_MS = 500L
        // Must match OnthecrowTunnelCore in the extension.
        private const val APP_GROUP_ID = "group.com.onthecrow.onthecrowvpn"
        private const val ERROR_KEY = "lastTunnelError"
        private const val GENERIC_FAILURE =
            "VPN failed to start — the server may be unreachable, the network is blocking it, or the configuration is unsupported."
    }
}
