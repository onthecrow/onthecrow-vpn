package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.analytics.AutoRestartReason
import com.onthecrow.deltavpn.analytics.AutoRestartResult
import com.onthecrow.deltavpn.connection.domain.ConfigValidationResult
import com.onthecrow.deltavpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.deltavpn.connection.model.ConfigRef
import com.onthecrow.deltavpn.connection.model.RemoteConfig
import com.onthecrow.deltavpn.coroutines.ApplicationScopeProvider
import com.onthecrow.deltavpn.vpn.ConnectionStatus
import com.onthecrow.deltavpn.vpn.VpnController
import com.onthecrow.deltavpn.vpn.domain.SplitTunnelRepository
import com.onthecrow.deltavpn.vpn.log.DebugLog
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode
import com.onthecrow.deltavpn.vpn.model.SplitTunnelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the VPN whenever the active config (ref or url) changes while the
 * tunnel is up. Sits in the application scope so background updates from
 * Firestore are honoured even when the UI is not on screen.
 */
internal class VpnSyncWorker(
    private val orchestrator: ConfigSourcesOrchestrator,
    private val vpnController: VpnController,
    private val prepareConnectionConfig: PrepareConnectionConfigUseCase,
    private val splitTunnelRepository: SplitTunnelRepository,
    private val analyticsManager: AnalyticsManager,
    scopeProvider: ApplicationScopeProvider,
) {
    private val scope = scopeProvider.scope
    private val activeKey = MutableStateFlow<ConfigKey?>(null)

    init {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        combine(
            orchestrator.state,
            vpnController.status,
            splitTunnelRepository.observe(),
        ) { sourcesState, status, splitTunnel ->
            ObservedTuple(
                ref = sourcesState.selected,
                config = sourcesState.selectedConfig,
                status = status,
                revokedActive = sourcesState.revokedActiveSelection,
                splitTunnel = splitTunnel,
            )
        }
            .distinctUntilChanged()
            .collect { tuple ->
                DebugLog.log(
                    "WORKER",
                    "observe: status=${tuple.status} ref=${tuple.ref} revoked=${tuple.revokedActive} activeKey=${activeKey.value != null}",
                )
                if (tuple.revokedActive) {
                    // The source holding the ACTIVE config was deleted remotely (orchestrator already
                    // removed it and cleared the selection). Stop the tunnel AND forget the system
                    // profile, regardless of current status. Revocation of a non-active source never
                    // reaches this branch and must not touch the tunnel.
                    activeKey.value = null
                    vpnController.revoke()
                    return@collect
                }
                when (tuple.status) {
                    ConnectionStatus.Connected -> handleConnected(tuple.ref, tuple.config, tuple.splitTunnel)
                    ConnectionStatus.Disconnected, is ConnectionStatus.Error -> {
                        activeKey.value = null
                    }
                    ConnectionStatus.Connecting, ConnectionStatus.Disconnecting, ConnectionStatus.PreparingPermission -> {
                        // Transient: don't interfere; let the in-flight transition finish.
                    }
                }
            }
    }

    private suspend fun handleConnected(ref: ConfigRef?, cfg: RemoteConfig?, splitTunnel: SplitTunnelSettings) {
        if (ref == null || cfg == null) {
            // No selection but VPN is up — leave it alone; UI surface will handle.
            return
        }
        // RemoteConfig.id is only unique within a source, so the key carries the full ref + url. The
        // split-tunnel settings are in the key too, so toggling them while connected restarts the tunnel
        // (the new exclusions are applied at the next establish, read from the now-updated holder).
        val current = ConfigKey(ref, cfg.url, splitTunnel.routingRelevant())
        val previous = activeKey.value
        if (previous == null) {
            // VPN was started outside our knowledge (initial connect); record and stop.
            activeKey.value = current
            return
        }
        if (previous == current) return
        // Config changed while connected — restart. Attribute it: a selection (ref/url) change vs a
        // split-tunnel-only change, so settings-driven reconnects are not counted as failures.
        val reason = if (previous.ref != current.ref || previous.url != current.url) {
            AutoRestartReason.SELECTION_CHANGE
        } else {
            AutoRestartReason.SPLIT_TUNNEL_CHANGE
        }
        restartWith(cfg, current, reason)
    }

    private suspend fun restartWith(cfg: RemoteConfig, key: ConfigKey, reason: AutoRestartReason) {
        // Validate BEFORE tearing anything down. Validation runs in the engine process, which is alive
        // and healthy while the tunnel is up — but the disconnect below KILLS it, and validating after
        // that raced the just-killed process into a DeadObjectException, so a split-tunnel change that
        // should have reconnected instead left the tunnel down until the user reconnected by hand.
        // Doing it first also means a genuinely bad new config never costs the working tunnel.
        DebugLog.log("WORKER", "restartWith: ${cfg.name} url=${cfg.url.take(40)} — validating")
        val xrayJson = when (val result = prepareConnectionConfig(cfg.url)) {
            is ConfigValidationResult.Valid -> result.xrayJson
            is ConfigValidationResult.Invalid -> {
                DebugLog.log("WORKER", "restartWith: INVALID config — ${result.message} — keeping current tunnel")
                analyticsManager.tunnelAutoRestart(reason, AutoRestartResult.KEPT_ON_INVALID)
                return
            }
        }
        DebugLog.log("WORKER", "restartWith: valid — disconnecting to reapply")
        vpnController.disconnect()
        // Wait for the service to fully tear down before reconnecting.
        // Accept Error too — a failed teardown still releases the tunnel.
        vpnController.status
            .filter { it is ConnectionStatus.Disconnected || it is ConnectionStatus.Error }
            .first()
        DebugLog.log("WORKER", "restartWith: torn down — reconnecting")
        vpnController.connect(xrayJson)
        activeKey.value = key
        analyticsManager.tunnelAutoRestart(reason, AutoRestartResult.RESTARTED)
    }

    /**
     * Drop the parts of the settings that don't reach the tunnel, so the key changes only when the
     * ROUTING would. In [SplitTunnelMode.OFF] the selection is remembered but not applied, and without
     * this every checkbox ticked in that mode would tear a live tunnel down and rebuild it for a
     * configuration that resolves identically.
     */
    private fun SplitTunnelSettings.routingRelevant(): SplitTunnelSettings =
        if (mode == SplitTunnelMode.OFF) copy(selectedPackages = emptySet()) else this

    private data class ConfigKey(val ref: ConfigRef, val url: String, val splitTunnel: SplitTunnelSettings)
    private data class ObservedTuple(
        val ref: ConfigRef?,
        val config: RemoteConfig?,
        val status: ConnectionStatus,
        val revokedActive: Boolean,
        val splitTunnel: SplitTunnelSettings,
    )
}
