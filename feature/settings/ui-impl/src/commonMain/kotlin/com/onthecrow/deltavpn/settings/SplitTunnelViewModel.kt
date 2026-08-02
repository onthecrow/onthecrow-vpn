package com.onthecrow.deltavpn.settings

import androidx.lifecycle.viewModelScope
import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.navigation.Navigator
import com.onthecrow.deltavpn.uicore.BaseViewModel
import com.onthecrow.deltavpn.vpn.domain.InstalledAppsProvider
import com.onthecrow.deltavpn.vpn.domain.SplitTunnelRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal class SplitTunnelViewModel(
    private val splitTunnelRepository: SplitTunnelRepository,
    private val installedAppsProvider: InstalledAppsProvider,
    private val analyticsManager: AnalyticsManager,
    private val navigator: Navigator,
    reducer: SplitTunnelReducer,
) : BaseViewModel<SplitTunnelEvent, SplitTunnelState, SplitTunnelReducer>(reducer) {

    init {
        eventFlow.onEach { event ->
            when (event) {
                SplitTunnelEvent.OnApplyClick -> apply()
                SplitTunnelEvent.OnBackClick -> navigator.back()
                else -> Unit
            }
        }.launchIn(viewModelScope)

        splitTunnelRepository.observe()
            .onEach { onEvent(SplitTunnelEvent.OnSettingsLoaded(it.mode, it.selectedPackages)) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            onEvent(SplitTunnelEvent.OnAppsLoaded(installedAppsProvider.installedApps()))
        }
    }

    /**
     * Persist the draft and leave.
     *
     * Nothing here reconnects: `VpnSyncWorker` keys the live tunnel on the split-tunnel settings among
     * other things, so writing them is what makes it tear down and re-establish with the new routing.
     * Doing it here as well would race that.
     */
    private fun apply() {
        val draft = state.value
        // Mode + a coarse app-count bucket only — never the package set (which reveals installed apps).
        analyticsManager.splitTunnelApplied(draft.mode.toAnalytics(), draft.selectedPackages.size)
        viewModelScope.launch {
            splitTunnelRepository.update {
                it.copy(mode = draft.mode, selectedPackages = draft.selectedPackages)
            }
            navigator.back()
        }
    }

    override fun getInitialState(): SplitTunnelState = SplitTunnelState()
}
