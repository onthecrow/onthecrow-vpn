package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.uicore.Event
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode

internal sealed interface SettingsEvent : Event {
    data class OnExcludePushChanged(val enabled: Boolean) : SettingsEvent
    data class OnAggressiveKeepaliveChanged(val enabled: Boolean) : SettingsEvent
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

    /** Separate from [OnSettingsLoaded]: recovery tuning has its own store and its own flow. */
    data class OnAggressiveKeepaliveLoaded(val enabled: Boolean) : SettingsEvent
}
