package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.uicore.State
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode

internal data class SettingsState(
    val excludePushServices: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val splitTunnelCount: Int = 0,
    /** Opt-in impatient recovery. Off = the default ladder, unchanged. */
    val aggressiveKeepalive: Boolean = false,
) : State {

    /** One line under the split-tunnel row saying what is configured, without opening the screen. */
    val splitTunnelSummary: String
        get() = when (splitTunnelMode) {
            SplitTunnelMode.OFF -> "Off — every app uses the VPN"
            SplitTunnelMode.BYPASS_SELECTED -> "Exclude — $splitTunnelCount ${appWord(splitTunnelCount)} bypass the VPN"
            SplitTunnelMode.ONLY_SELECTED -> "Include — only $splitTunnelCount ${appWord(splitTunnelCount)} use the VPN"
        }

    private fun appWord(count: Int) = if (count == 1) "app" else "apps"
}
