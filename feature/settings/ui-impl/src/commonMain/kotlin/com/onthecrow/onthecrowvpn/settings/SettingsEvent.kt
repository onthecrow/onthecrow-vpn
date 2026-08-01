package com.onthecrow.onthecrowvpn.settings

import com.onthecrow.onthecrowvpn.uicore.Event
import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelMode

internal sealed interface SettingsEvent : Event {
    data class OnExcludePushChanged(val enabled: Boolean) : SettingsEvent
    data object OnSplitTunnelClick : SettingsEvent
    /** The diagnostic-log share sheet was opened (long-press on the version footer). */
    data object OnLogShared : SettingsEvent
    data object OnBackClick : SettingsEvent

    // Internal
    data class OnSettingsLoaded(
        val excludePushServices: Boolean,
        val splitTunnelMode: SplitTunnelMode,
        val splitTunnelCount: Int,
    ) : SettingsEvent
}
