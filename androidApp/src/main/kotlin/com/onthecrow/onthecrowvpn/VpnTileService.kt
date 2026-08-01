package com.onthecrow.onthecrowvpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.onthecrow.onthecrowvpn.analytics.AnalyticsManager
import com.onthecrow.onthecrowvpn.analytics.QsBlockedReason
import com.onthecrow.onthecrowvpn.analytics.QsTileAction
import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.ObserveConfigSourcesUseCase
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.coroutines.ApplicationScopeProvider
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import com.onthecrow.onthecrowvpn.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Quick Settings tile: tap toggles the tunnel using the currently selected config, and the subtitle
 * shows which config that is. Long-press opens the app — that's wired in the manifest via the
 * `QS_TILE_PREFERENCES` intent-filter on [MainActivity], not here.
 *
 * Runs in the MAIN process, so the Koin graph is up (the Application initialises it there) and the
 * same [VpnController] the UI uses is reachable.
 */
class VpnTileService : TileService(), KoinComponent {

    private val vpnController: VpnController by inject()
    private val observeConfigSources: ObserveConfigSourcesUseCase by inject()
    private val prepareConnectionConfig: PrepareConnectionConfigUseCase by inject()
    private val applicationScope: ApplicationScopeProvider by inject()
    private val analyticsManager: AnalyticsManager by inject()

    /** Drives tile rendering only; cancelled as soon as the QS panel closes. */
    private var listeningScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listeningScope = scope
        combine(
            vpnController.status,
            observeConfigSources().map { it.selectedConfig?.name },
        ) { status, configName -> status to configName }
            .onEach { (status, configName) -> render(status, configName) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        listeningScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val status = vpnController.status.value
        val busyOrUp = status == ConnectionStatus.Connected ||
            status == ConnectionStatus.Connecting ||
            status == ConnectionStatus.PreparingPermission
        if (busyOrUp) {
            analyticsManager.qsTileAction(QsTileAction.DISCONNECT)
            // Toggle off. Runs on the application scope: the tile stops listening the moment the panel
            // collapses, and this work must outlive that.
            applicationScope.scope.launch { vpnController.disconnect() }
            return
        }
        // The system consent dialog needs an Activity, which a tile cannot show — hand off to the app.
        if (VpnService.prepare(this) != null) {
            analyticsManager.qsTileAction(QsTileAction.BLOCKED, QsBlockedReason.NO_PERMISSION)
            openApp()
            return
        }
        applicationScope.scope.launch { connectSelected() }
    }

    private suspend fun connectSelected() {
        val config = observeConfigSources().first().selectedConfig
        if (config == null) {
            analyticsManager.qsTileAction(QsTileAction.BLOCKED, QsBlockedReason.NO_CONFIG)
            // Nothing selected yet — let the user pick one in the app.
            withContext(Dispatchers.Main) { openApp() }
            return
        }
        when (val result = prepareConnectionConfig(config.url)) {
            is ConfigValidationResult.Valid -> {
                analyticsManager.qsTileAction(QsTileAction.CONNECT)
                vpnController.connect(result.xrayJson)
            }
            // Validation failed (dead link / revoked): surface it where we can actually explain it.
            is ConfigValidationResult.Invalid -> {
                analyticsManager.qsTileAction(QsTileAction.BLOCKED, QsBlockedReason.CONFIG_INVALID)
                withContext(Dispatchers.Main) { openApp() }
            }
        }
    }

    private fun render(status: ConnectionStatus, configName: String?) {
        val tile = qsTile ?: return
        tile.state = if (status == ConnectionStatus.Connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status) {
                ConnectionStatus.Connecting, ConnectionStatus.PreparingPermission ->
                    getString(R.string.tile_connecting)
                ConnectionStatus.Disconnecting -> getString(R.string.tile_disconnecting)
                is ConnectionStatus.Error -> getString(R.string.tile_error)
                else -> configName ?: getString(R.string.tile_no_config)
            }
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // startActivityAndCollapse(Intent) throws on API 34+; it must be a PendingIntent.
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure {
            // Panel already gone (e.g. we got here after async validation) — plain launch still works.
            runCatching { startActivity(intent) }
        }
    }
}
