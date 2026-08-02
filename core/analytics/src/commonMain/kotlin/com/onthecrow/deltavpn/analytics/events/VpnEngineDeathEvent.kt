package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.EngineDeathReason

/** `vpn_engine_death` — the `:xray` engine process died. [reason] is mapped from the exit reason int. */
internal data class VpnEngineDeathEvent(
    val reason: EngineDeathReason,
) : AnalyticsEvent {
    override val name = "vpn_engine_death"
    override fun params() = mapOf("reason" to reason.raw())
}
