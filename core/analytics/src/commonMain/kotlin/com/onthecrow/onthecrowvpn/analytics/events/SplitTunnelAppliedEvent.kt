package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.SplitTunnelMode

/**
 * `split_tunnel_applied` — a split-tunnel edit was committed. Carries the mode and a coarse app-count
 * bucket only — never the package names, the app list, or the exact count.
 */
internal data class SplitTunnelAppliedEvent(
    val mode: SplitTunnelMode,
    val appCount: Int,
) : AnalyticsEvent {
    override val name = "split_tunnel_applied"
    override fun params() = mapOf(
        "mode" to mode.raw(),
        "app_count_bucket" to appCountBucket(appCount),
    )
}
