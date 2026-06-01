package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.coroutines.ApplicationScopeProvider
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import com.onthecrow.onthecrowvpn.vpn.VpnController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the VPN whenever the active config (id or url) changes while the
 * tunnel is up. Sits in the application scope so background updates from
 * Firestore are honoured even when the UI is not on screen.
 */
internal class VpnSyncWorker(
    private val orchestrator: ActiveBundleOrchestrator,
    private val vpnController: VpnController,
    private val prepareConnectionConfig: PrepareConnectionConfigUseCase,
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
        ) { bundleState, status ->
            val cfg = bundleState.bundle?.configs?.firstOrNull { it.id == bundleState.selectedConfigId }
            ObservedTuple(cfg, status)
        }
            .distinctUntilChanged()
            .collect { tuple ->
                val cfg = tuple.config
                when (val status = tuple.status) {
                    ConnectionStatus.Connected -> handleConnected(cfg)
                    ConnectionStatus.Disconnected, is ConnectionStatus.Error -> {
                        activeKey.value = null
                    }
                    ConnectionStatus.Connecting, ConnectionStatus.Disconnecting, ConnectionStatus.PreparingPermission -> {
                        // Transient: don't interfere; let the in-flight transition finish.
                        @Suppress("unused")
                        status
                    }
                }
            }
    }

    private suspend fun handleConnected(cfg: RemoteConfig?) {
        if (cfg == null) {
            // No selection but VPN is up — leave it alone; UI surface will handle.
            return
        }
        val current = ConfigKey(cfg.id, cfg.url)
        val previous = activeKey.value
        if (previous == null) {
            // VPN was started outside our knowledge (initial connect); record and stop.
            activeKey.value = current
            return
        }
        if (previous == current) return
        // Config changed while connected — restart.
        restartWith(cfg, current)
    }

    private suspend fun restartWith(cfg: RemoteConfig, key: ConfigKey) {
        vpnController.disconnect()
        // Wait for the service to fully tear down before reconnecting.
        // Accept Error too — a failed teardown still releases the tunnel.
        vpnController.status
            .filter { it is ConnectionStatus.Disconnected || it is ConnectionStatus.Error }
            .first()
        when (val result = prepareConnectionConfig(cfg.url)) {
            is ConfigValidationResult.Valid -> {
                vpnController.connect(result.xrayJson)
                activeKey.value = key
            }
            is ConfigValidationResult.Invalid -> {
                // Validation failed for the new config: surface is via VpnController error
                // status / snackbar pathway; nothing else to do here.
            }
        }
    }

    private data class ConfigKey(val id: String, val url: String)
    private data class ObservedTuple(val config: RemoteConfig?, val status: ConnectionStatus)
}
