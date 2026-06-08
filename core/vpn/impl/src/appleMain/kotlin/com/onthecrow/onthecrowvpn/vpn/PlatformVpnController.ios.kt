package com.onthecrow.onthecrowvpn.vpn

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusConnected
import platform.NetworkExtension.NEVPNStatusConnecting
import platform.NetworkExtension.NEVPNStatusDisconnecting
import platform.NetworkExtension.NEVPNStatusReasserting

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
 *
 * All NetworkExtension plumbing lives in the shared [AppleTunnelManager] (reused by the macOS
 * bridge). This class only adds the iOS-specific concerns: the status StateFlow, the main-run-loop
 * poller, and turning a failed connection attempt into an informative Error.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformVpnController : VpnController {
    private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = mutableStatus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tunnel = AppleTunnelManager(providerBundleId = extensionBundleId())

    // True between asking the system to connect and reaching Connected. If the OS instead drops
    // back to Disconnected while this is set, the tunnel failed — we then surface the reason the
    // extension wrote to the shared App Group store (or a generic message).
    private var pendingConnect = false

    init {
        reconcile()
        scope.launch {
            while (isActive) {
                // StateFlow dedups, so this only emits when the OS status actually changes.
                tunnel.currentStatus()?.let { publish(mapStatus(it)) }
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
                    mutableStatus.value = ConnectionStatus.Error(tunnel.readSharedError() ?: GENERIC_FAILURE)
                } else {
                    mutableStatus.value = mapped
                }
            }
            else -> mutableStatus.value = mapped
        }
    }

    /** Adopt an existing system profile and publish its real status at launch. */
    private fun reconcile() {
        scope.launch {
            val status = tunnel.loadExisting()
            mutableStatus.value = status?.let { mapStatus(it) } ?: ConnectionStatus.Disconnected
        }
    }

    override suspend fun connect(xrayJson: String): ConnectResult {
        return try {
            val started = tunnel.connect(xrayJson, beforeStart = { pendingConnect = true })
            if (started == null) {
                // Async outcome now driven by the poller: Connecting → Connected, or a drop back
                // to Disconnected which publish() turns into an Error with the extension's reason.
                ConnectResult.Started
            } else {
                // Synchronous failure — the caller surfaces this message itself.
                pendingConnect = false
                tunnel.currentStatus()?.let { mutableStatus.value = mapStatus(it) }
                ConnectResult.Failed(started)
            }
        } catch (t: Throwable) {
            pendingConnect = false
            val message = t.message ?: "Failed to start VPN"
            mutableStatus.value = tunnel.currentStatus()?.let { mapStatus(it) }
                ?: ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    override suspend fun disconnect() {
        // User-initiated stop is not a failure.
        pendingConnect = false
        if (!tunnel.disconnect()) {
            mutableStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun mapStatus(status: NEVPNStatus): ConnectionStatus = when (status) {
        NEVPNStatusConnected -> ConnectionStatus.Connected
        NEVPNStatusConnecting, NEVPNStatusReasserting -> ConnectionStatus.Connecting
        NEVPNStatusDisconnecting -> ConnectionStatus.Disconnecting
        else -> ConnectionStatus.Disconnected
    }

    private companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val GENERIC_FAILURE =
            "VPN failed to start — the server may be unreachable, the network is blocking it, or the configuration is unsupported."

        // Derived from the running app's bundle id so it matches both the .dev (debug)
        // and release configurations automatically — the extension must be a child id.
        private fun extensionBundleId(): String =
            (NSBundle.mainBundle.bundleIdentifier ?: "com.onthecrow.onthecrowvpn.OnthecrowVPN") + ".PacketTunnel"
    }
}
