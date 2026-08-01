package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.TunRebuildOutcome

/** `vpn_tun_rebuild` — outcome of rebuilding the tun on a confirmed path change. */
internal data class VpnTunRebuildEvent(
    val outcome: TunRebuildOutcome,
) : AnalyticsEvent {
    override val name = "vpn_tun_rebuild"
    override fun params() = mapOf("outcome" to outcome.raw())
}
