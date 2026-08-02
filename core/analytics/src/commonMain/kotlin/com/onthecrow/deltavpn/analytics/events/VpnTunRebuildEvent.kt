package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.TunRebuildOutcome

/** `vpn_tun_rebuild` — outcome of rebuilding the tun on a confirmed path change. */
internal data class VpnTunRebuildEvent(
    val outcome: TunRebuildOutcome,
) : AnalyticsEvent {
    override val name = "vpn_tun_rebuild"
    override fun params() = mapOf("outcome" to outcome.raw())
}
