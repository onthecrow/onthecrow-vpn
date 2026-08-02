package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode
import com.onthecrow.deltavpn.analytics.SplitTunnelMode as AnalyticsSplitTunnelMode

/** Domain routing mode -> the analytics enum (a category; never the selected packages). */
internal fun SplitTunnelMode.toAnalytics(): AnalyticsSplitTunnelMode = when (this) {
    SplitTunnelMode.OFF -> AnalyticsSplitTunnelMode.OFF
    SplitTunnelMode.BYPASS_SELECTED -> AnalyticsSplitTunnelMode.EXCLUDE
    SplitTunnelMode.ONLY_SELECTED -> AnalyticsSplitTunnelMode.INCLUDE
}
