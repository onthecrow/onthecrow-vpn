package com.onthecrow.onthecrowvpn.connection

import androidx.lifecycle.viewModelScope
import com.onthecrow.onthecrowvpn.connection.domain.AddSourceResult
import com.onthecrow.onthecrowvpn.connection.domain.AddSourceUseCase
import com.onthecrow.onthecrowvpn.connection.domain.CollapsedGroupsUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.ObserveConfigSourcesUseCase
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.RefreshResult
import com.onthecrow.onthecrowvpn.connection.domain.RefreshSourceUseCase
import com.onthecrow.onthecrowvpn.connection.domain.RemoveSourceUseCase
import com.onthecrow.onthecrowvpn.connection.domain.SelectConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.navigation.Navigator
import com.onthecrow.onthecrowvpn.settings.SettingsDestination
import com.onthecrow.onthecrowvpn.uicore.BaseViewModel
import com.onthecrow.onthecrowvpn.vpn.ConnectResult
import com.onthecrow.onthecrowvpn.vpn.VpnController
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionRequester
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal class ConnectionViewModel(
    private val observeConfigSourcesUseCase: ObserveConfigSourcesUseCase,
    private val addSourceUseCase: AddSourceUseCase,
    private val removeSourceUseCase: RemoveSourceUseCase,
    private val refreshSourceUseCase: RefreshSourceUseCase,
    private val selectConfigUseCase: SelectConfigUseCase,
    private val prepareConnectionConfigUseCase: PrepareConnectionConfigUseCase,
    private val collapsedGroupsUseCase: CollapsedGroupsUseCase,
    private val vpnController: VpnController,
    private val vpnPermissionRequester: VpnPermissionRequester,
    private val navigator: Navigator,
    reducer: ConnectionReducer,
) : BaseViewModel<ConnectionEvent, ConnectionState, ConnectionReducer>(reducer) {

    init {
        eventFlow.onEach { event ->
            when (event) {
                is ConnectionEvent.OnAddFromClipboard -> handleAddFromClipboard(event)
                is ConnectionEvent.OnConfigSelected -> handleConfigSelected(event.ref)
                is ConnectionEvent.OnSetGroupCollapsed -> handleSetGroupCollapsed(event)
                is ConnectionEvent.OnRefreshSourceClick -> handleRefreshSource(event.sourceId)
                is ConnectionEvent.OnDeleteSourceClick -> handleDeleteSource(event.sourceId)
                ConnectionEvent.OnConnectClick -> handleConnectClick()
                ConnectionEvent.OnDisconnectClick -> handleDisconnectClick()
                ConnectionEvent.OnSettingsClick -> navigator.navigate(SettingsDestination)
                else -> Unit
            }
        }.launchIn(viewModelScope)

        // Resolve the initial collapsed set BEFORE any group composes. The screen withholds the group
        // list until this lands ([ConnectionState.collapsedLoaded]); otherwise groups would first render
        // expanded and then animate the collapse on every launch. Cold start only — the ViewModel is
        // created once per process — so the active-group expand never fires on resume.
        viewModelScope.launch {
            val saved = collapsedGroupsUseCase.observe().first()
            // Wait for the groups to materialize only when a selection exists (so its group can be
            // found); with nothing selected — including a fresh install — resolve immediately.
            val sources = observeConfigSourcesUseCase().first { it.selected == null || it.groups.isNotEmpty() }
            // Force-expand the group holding the active config so it's visible without scrolling.
            // Done in-memory only (not persisted) so the user's saved "collapsed" for it survives.
            val activeGroup = sources.selected?.let { ref ->
                sources.groups.firstOrNull { group ->
                    group.sourceId == ref.sourceId || group.rows.any { it.ref == ref }
                }?.sourceId
            }
            onEvent(ConnectionEvent.OnCollapsedLoaded(saved - setOfNotNull(activeGroup)))
        }

        observeConfigSourcesUseCase()
            .onEach { sourcesState ->
                onEvent(ConnectionEvent.OnSourcesChanged(sourcesState))
                // One-shot: sources were deleted remotely; the worker tears the VPN down if the active
                // config was affected — here we just inform the user.
                sourcesState.revokedSourceTitles.forEach { title ->
                    onEvent(
                        ConnectionEvent.OnSnackbarRequested(
                            "Subscription “$title” is no longer available",
                            isError = true,
                        ),
                    )
                }
            }
            .launchIn(viewModelScope)

        vpnController.status
            .onEach { onEvent(ConnectionEvent.OnConnectionStatusChanged(it)) }
            .launchIn(viewModelScope)
    }

    override fun getInitialState(): ConnectionState = ConnectionState()

    private fun handleAddFromClipboard(event: ConnectionEvent.OnAddFromClipboard) {
        val text = event.clipboardText?.trim()
        if (text.isNullOrEmpty()) {
            onEvent(ConnectionEvent.OnSnackbarRequested("Clipboard is empty", isError = true))
            return
        }
        viewModelScope.launch {
            val result = when (event.kind) {
                ConnectionEvent.AddKind.SUBSCRIPTION_ID -> addSourceUseCase.addSubscriptionId(text)
                ConnectionEvent.AddKind.SUBSCRIPTION_URL -> addSourceUseCase.addSubscriptionUrl(text)
                ConnectionEvent.AddKind.XRAY_LINK -> addSourceUseCase.addXrayLink(text)
            }
            when (result) {
                AddSourceResult.Added -> Unit // the new group appears via the sources flow
                is AddSourceResult.Invalid ->
                    onEvent(ConnectionEvent.OnSnackbarRequested(result.message, isError = true))
            }
        }
    }

    private fun handleSetGroupCollapsed(event: ConnectionEvent.OnSetGroupCollapsed) {
        // Per-key persistence: the in-memory cold-start expand override is never written back, so a
        // user's saved "collapsed" for the active group survives even though we expand it on launch.
        viewModelScope.launch { collapsedGroupsUseCase.setCollapsed(event.groupKey, event.collapsed) }
    }

    private fun handleRefreshSource(sourceId: String) {
        if (sourceId in state.value.refreshingSourceIds) return // refresh already in flight
        viewModelScope.launch {
            onEvent(ConnectionEvent.OnRefreshStateChanged(sourceId, refreshing = true))
            val result = refreshSourceUseCase(sourceId)
            onEvent(ConnectionEvent.OnRefreshStateChanged(sourceId, refreshing = false))
            if (result is RefreshResult.Failed) {
                onEvent(ConnectionEvent.OnSnackbarRequested(result.message, isError = true))
            }
        }
    }

    private fun handleDeleteSource(sourceId: String) {
        viewModelScope.launch { removeSourceUseCase(sourceId) }
    }

    private fun handleConfigSelected(ref: ConfigRef) {
        viewModelScope.launch {
            // Persist only. VpnSyncWorker is the SINGLE owner of live-tunnel switches: it reacts to
            // the selection change while Connected (disconnect → await → prepare → connect,
            // sequentially). Doing the same dance here too made TWO actors race one switch — two
            // concurrent libXray validate/testXray calls (shared global state + same config file)
            // plus double CONNECT intents, which could zombify the fresh xray instance: tun up,
            // upstream dead, client offline on the server.
            selectConfigUseCase(ref)
        }
    }

    private fun handleConnectClick() {
        if (state.value.isConnected) {
            handleDisconnectClick()
            return
        }
        val cfg = state.value.selectedConfig
        if (cfg == null) {
            onEvent(ConnectionEvent.OnSnackbarRequested("Select a configuration first"))
            return
        }
        viewModelScope.launch { startConnection(cfg) }
    }

    /** Validate the config's share link, ensure VPN permission, then ask the controller to connect. */
    private suspend fun startConnection(config: RemoteConfig) {
        when (val result = prepareConnectionConfigUseCase(config.url)) {
            is ConfigValidationResult.Invalid -> {
                onEvent(ConnectionEvent.OnSnackbarRequested(result.message, isError = true))
            }
            is ConfigValidationResult.Valid -> when (val perm = vpnPermissionRequester.requestPermission()) {
                VpnPermissionResult.Granted -> {
                    // Non-blocking advisory (e.g. Windows: a system proxy that may bypass the tunnel).
                    vpnController.connectNotice()?.let { onEvent(ConnectionEvent.OnSnackbarRequested(it)) }
                    when (val outcome = vpnController.connect(result.xrayJson)) {
                        ConnectResult.Started -> Unit
                        is ConnectResult.Failed ->
                            onEvent(ConnectionEvent.OnSnackbarRequested(outcome.message, isError = true))
                    }
                }
                is VpnPermissionResult.Denied ->
                    onEvent(ConnectionEvent.OnSnackbarRequested(perm.message, isError = true))
            }
        }
    }

    private fun handleDisconnectClick() {
        viewModelScope.launch { vpnController.disconnect() }
    }
}
