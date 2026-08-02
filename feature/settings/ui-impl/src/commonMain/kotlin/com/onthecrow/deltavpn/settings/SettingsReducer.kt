package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.uicore.Reducer

internal class SettingsReducer : Reducer<SettingsState, SettingsEvent> {
    override suspend fun reduce(
        state: SettingsState,
        event: SettingsEvent,
    ): SettingsState = when (event) {
        is SettingsEvent.OnSettingsLoaded -> state.copy(
            excludePushServices = event.excludePushServices,
            splitTunnelMode = event.splitTunnelMode,
            splitTunnelCount = event.splitTunnelCount,
        )
        is SettingsEvent.OnAggressiveKeepaliveLoaded -> state.copy(aggressiveKeepalive = event.enabled)
        // Optimistic — the toggle reflects immediately; persistence + reload confirm it.
        is SettingsEvent.OnExcludePushChanged -> state.copy(excludePushServices = event.enabled)
        is SettingsEvent.OnAggressiveKeepaliveChanged -> state.copy(aggressiveKeepalive = event.enabled)
        SettingsEvent.OnSplitTunnelClick,
        SettingsEvent.OnLogShared,
        SettingsEvent.OnBackClick,
        -> state
    }
}
