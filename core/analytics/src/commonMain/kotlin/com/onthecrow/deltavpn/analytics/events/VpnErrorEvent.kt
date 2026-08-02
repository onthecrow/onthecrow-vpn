package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.VpnErrorCategory

/** `vpn_error` — a terminal connection failure. [category] is mapped from the error TYPE, not its message. */
internal data class VpnErrorEvent(
    val category: VpnErrorCategory,
    val terminal: Boolean,
) : AnalyticsEvent {
    override val name = "vpn_error"
    override fun params() = mapOf(
        "category" to category.raw(),
        "terminal" to terminal.toString(),
    )
}
