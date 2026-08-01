package com.onthecrow.onthecrowvpn.settings

import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelMode
import com.onthecrow.onthecrowvpn.analytics.SplitTunnelMode as AnalyticsSplitTunnelMode

/** Domain routing mode -> the analytics enum (a category; never the selected packages). */
internal fun SplitTunnelMode.toAnalytics(): AnalyticsSplitTunnelMode = when (this) {
    SplitTunnelMode.OFF -> AnalyticsSplitTunnelMode.OFF
    SplitTunnelMode.BYPASS_SELECTED -> AnalyticsSplitTunnelMode.EXCLUDE
    SplitTunnelMode.ONLY_SELECTED -> AnalyticsSplitTunnelMode.INCLUDE
}
