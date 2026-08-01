package com.onthecrow.onthecrowvpn.analytics.events

/** `split_tunnel_opened` — the split-tunnel screen was opened (feature discovery). No parameters. */
internal class SplitTunnelOpenedEvent : AnalyticsEvent {
    override val name = "split_tunnel_opened"
    override fun params(): Map<String, String> = emptyMap()
}
