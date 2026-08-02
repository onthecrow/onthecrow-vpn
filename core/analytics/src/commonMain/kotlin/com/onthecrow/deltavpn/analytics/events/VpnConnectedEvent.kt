package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.ConnectVia

/** `vpn_connected` — the tunnel reached Connected. [via] separates a fresh connect from a re-establish. */
internal data class VpnConnectedEvent(
    val via: ConnectVia,
) : AnalyticsEvent {
    override val name = "vpn_connected"
    override fun params() = mapOf("via" to via.raw())
}
