package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.ConfirmVia

/**
 * `vpn_tunnel_confirmed` — first probe of a session actually carried traffic (honest time-to-usable).
 * Emit once per session; the elapsed time is coarse-bucketed.
 */
internal data class VpnTunnelConfirmedEvent(
    val firstProbeMs: Long,
    val via: ConfirmVia,
) : AnalyticsEvent {
    override val name = "vpn_tunnel_confirmed"
    override fun params() = mapOf(
        "first_probe_bucket" to shortDurationBucket(firstProbeMs),
        "via" to via.raw(),
    )
}
