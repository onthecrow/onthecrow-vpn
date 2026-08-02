package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.uicore.Event
import com.onthecrow.deltavpn.vpn.model.InstalledApp
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode

internal sealed interface SplitTunnelEvent : Event {
    data class OnModeChanged(val mode: SplitTunnelMode) : SplitTunnelEvent
    data class OnAppToggled(val packageName: String) : SplitTunnelEvent
    data class OnQueryChanged(val query: String) : SplitTunnelEvent
    data object OnQueryCleared : SplitTunnelEvent
    data object OnApplyClick : SplitTunnelEvent
    data object OnBackClick : SplitTunnelEvent

    // Internal
    data class OnAppsLoaded(val apps: List<InstalledApp>) : SplitTunnelEvent

    /**
     * The persisted settings arrived (or changed underneath us). Only seeds the draft while there is
     * nothing pending — otherwise a background write would silently discard what the user is editing.
     */
    data class OnSettingsLoaded(
        val mode: SplitTunnelMode,
        val selectedPackages: Set<String>,
    ) : SplitTunnelEvent
}
