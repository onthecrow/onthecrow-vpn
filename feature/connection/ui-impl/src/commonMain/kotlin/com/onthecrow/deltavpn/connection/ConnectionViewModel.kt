package com.onthecrow.deltavpn.connection

import androidx.lifecycle.viewModelScope
import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.analytics.ConfigInvalidReason
import com.onthecrow.deltavpn.analytics.ConnectFailureCategory
import com.onthecrow.deltavpn.analytics.ConsentResult
import com.onthecrow.deltavpn.analytics.DisconnectEntryPoint
import com.onthecrow.deltavpn.analytics.VpnPermissionOutcome
import com.onthecrow.deltavpn.analytics.SourceKind as AnalyticsSourceKind
import com.onthecrow.deltavpn.connection.domain.AddSourceResult
import com.onthecrow.deltavpn.connection.domain.AddSourceUseCase
import com.onthecrow.deltavpn.connection.domain.CollapsedGroupsUseCase
import com.onthecrow.deltavpn.connection.domain.ConfigValidationResult
import com.onthecrow.deltavpn.connection.domain.ObserveConfigSourcesUseCase
import com.onthecrow.deltavpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.deltavpn.connection.domain.RefreshResult
import com.onthecrow.deltavpn.connection.domain.RefreshSourceUseCase
import com.onthecrow.deltavpn.connection.domain.RemoveSourceUseCase
import com.onthecrow.deltavpn.connection.domain.SelectConfigUseCase
import com.onthecrow.deltavpn.connection.domain.VpnConsentRepository
import com.onthecrow.deltavpn.connection.model.ConfigRef
import com.onthecrow.deltavpn.connection.model.RemoteConfig
import com.onthecrow.deltavpn.navigation.Navigator
import com.onthecrow.deltavpn.settings.SettingsDestination
import com.onthecrow.deltavpn.uicore.BaseViewModel
import com.onthecrow.deltavpn.vpn.ConnectResult
import com.onthecrow.deltavpn.vpn.VpnController
import com.onthecrow.deltavpn.vpn.VpnPermissionRequester
import com.onthecrow.deltavpn.vpn.VpnPermissionResult
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
    private val vpnConsentRepository: VpnConsentRepository,
    private val analyticsManager: AnalyticsManager,
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
                ConnectionEvent.OnConsentAccepted -> handleConsentAccepted()
                ConnectionEvent.OnConsentDeclined ->
                    analyticsManager.vpnConsentResult(ConsentResult.DECLINED)
                ConnectionEvent.OnDisconnectClick -> handleDisconnectClick()
                ConnectionEvent.OnSettingsClick -> {
                    analyticsManager.settingsOpened()
                    navigator.navigate(SettingsDestination)
                }
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
                // config was affected. `revokedActiveSelection` = the removed source held the selection,
                // so it was an involuntary disconnect (was_active=true).
                if (sourcesState.revokedActiveSelection) {
                    analyticsManager.subscriptionRevokedRemote(wasActive = true)
                } else if (sourcesState.revokedSourceTitles.isNotEmpty()) {
                    analyticsManager.subscriptionRevokedRemote(wasActive = false)
                }
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
        val previous = state.value.selected
        analyticsManager.configSelected(
            sourceKind = analyticsSourceKindOf(ref),
            isSwitch = previous != null && previous != ref,
        )
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
        analyticsManager.connectTapped(
            hadSelection = state.value.selectedConfig != null,
            alreadyRunning = state.value.isConnected || state.value.isBusy,
        )
        // Connected OR mid-transition both mean "stop". Only the second half is new: a tap while
        // Connecting used to start a SECOND connect, so a tunnel that never finished coming up had no
        // way out at all — the one control on the screen could only ask again for the thing that was
        // already stuck. Disconnect is safe from any state; the service stands down wherever it is.
        if (state.value.isConnected || state.value.isBusy) {
            handleDisconnectClick()
            return
        }
        val cfg = state.value.selectedConfig
        if (cfg == null) {
            onEvent(ConnectionEvent.OnSnackbarRequested("Select a configuration first"))
            return
        }
        viewModelScope.launch {
            // Play's VpnService policy: the prominent disclosure must be shown and consent given
            // BEFORE the system VpnService.prepare() dialog (which startConnection triggers). Read the
            // persisted flag authoritatively at tap time — first connect after install raises the
            // disclosure; every connect after acceptance goes straight through.
            if (vpnConsentRepository.observe().first()) {
                startConnection(cfg)
            } else {
                analyticsManager.vpnConsentShown()
                onEvent(ConnectionEvent.OnConsentRequested)
            }
        }
    }

    /** Accept persists the grant (so the disclosure never shows again) and continues the connect. */
    private fun handleConsentAccepted() {
        analyticsManager.vpnConsentResult(ConsentResult.ACCEPTED)
        viewModelScope.launch {
            vpnConsentRepository.grant()
            val cfg = state.value.selectedConfig ?: return@launch
            startConnection(cfg)
        }
    }

    /** Validate the config's share link, ensure VPN permission, then ask the controller to connect. */
    private suspend fun startConnection(config: RemoteConfig) {
        when (val result = prepareConnectionConfigUseCase(config.url)) {
            is ConfigValidationResult.Invalid -> {
                // A link that validated at add time can be dead/revoked at connect time.
                analyticsManager.connectConfigValidation(ConfigInvalidReason.ENGINE_REJECTED)
                onEvent(ConnectionEvent.OnSnackbarRequested(result.message, isError = true))
            }
            is ConfigValidationResult.Valid -> {
                analyticsManager.connectConfigValidation(invalidReason = null)
                when (val perm = vpnPermissionRequester.requestPermission()) {
                    VpnPermissionResult.Granted -> {
                        analyticsManager.vpnPermissionResult(VpnPermissionOutcome.GRANTED)
                        // Non-blocking advisory (e.g. Windows: a system proxy that may bypass the tunnel).
                        vpnController.connectNotice()?.let { onEvent(ConnectionEvent.OnSnackbarRequested(it)) }
                        when (val outcome = vpnController.connect(result.xrayJson)) {
                            ConnectResult.Started -> analyticsManager.connectResult(failureCategory = null)
                            is ConnectResult.Failed -> {
                                analyticsManager.connectResult(ConnectFailureCategory.SERVICE_START_FAILURE)
                                onEvent(ConnectionEvent.OnSnackbarRequested(outcome.message, isError = true))
                            }
                        }
                    }
                    is VpnPermissionResult.Denied -> {
                        analyticsManager.vpnPermissionResult(VpnPermissionOutcome.DENIED)
                        onEvent(ConnectionEvent.OnSnackbarRequested(perm.message, isError = true))
                    }
                }
            }
        }
    }

    private fun handleDisconnectClick() {
        analyticsManager.disconnectTapped(DisconnectEntryPoint.IN_APP)
        viewModelScope.launch { vpnController.disconnect() }
    }

    /** Analytics source-kind of a ref, resolved from the group that holds it (a category, never an id). */
    private fun analyticsSourceKindOf(ref: ConfigRef): AnalyticsSourceKind {
        val group = state.value.groups.firstOrNull { g ->
            g.sourceId == ref.sourceId || g.rows.any { it.ref == ref }
        }
        return when (group?.kind) {
            com.onthecrow.deltavpn.connection.model.SourceKind.SUBSCRIPTION_URL ->
                AnalyticsSourceKind.SUBSCRIPTION_URL
            com.onthecrow.deltavpn.connection.model.SourceKind.XRAY_LINK ->
                AnalyticsSourceKind.XRAY_LINK
            // SUBSCRIPTION_ID or an unresolved group (should not happen for a selectable ref).
            else -> AnalyticsSourceKind.SUBSCRIPTION_ID
        }
    }
}
