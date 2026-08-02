package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.KeepaliveWindow

/**
 * `vpn_keepalive_health` — one per session, rolled up from keepalive probes. DEAD and INCONCLUSIVE
 * (Doze-frozen) counts are reported separately so a Doze freeze is never scored as a tunnel failure.
 */
internal data class VpnKeepaliveHealthEvent(
    val window: KeepaliveWindow,
    val deadCount: Int,
    val inconclusiveCount: Int,
) : AnalyticsEvent {
    override val name = "vpn_keepalive_health"
    override fun params() = mapOf(
        "window_result" to window.raw(),
        "dead_bucket" to deadProbeBucket(deadCount),
        "inconclusive_bucket" to inconclusiveProbeBucket(inconclusiveCount),
    )
}
