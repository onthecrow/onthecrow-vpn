package com.onthecrow.onthecrowvpn.settings

import androidx.lifecycle.viewModelScope
import com.onthecrow.onthecrowvpn.navigation.Navigator
import com.onthecrow.onthecrowvpn.uicore.BaseViewModel
import com.onthecrow.onthecrowvpn.vpn.domain.SplitTunnelRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal class SettingsViewModel(
    private val splitTunnelRepository: SplitTunnelRepository,
    private val navigator: Navigator,
    reducer: SettingsReducer,
) : BaseViewModel<SettingsEvent, SettingsState, SettingsReducer>(reducer) {

    init {
        eventFlow.onEach { event ->
            when (event) {
                is SettingsEvent.OnExcludePushChanged -> viewModelScope.launch {
                    splitTunnelRepository.update { it.copy(excludePushServices = event.enabled) }
                }
                SettingsEvent.OnBackClick -> navigator.back()
                else -> Unit
            }
        }.launchIn(viewModelScope)

        splitTunnelRepository.observe()
            .onEach { onEvent(SettingsEvent.OnSettingsLoaded(it.excludePushServices)) }
            .launchIn(viewModelScope)
    }

    override fun getInitialState(): SettingsState = SettingsState()
}
