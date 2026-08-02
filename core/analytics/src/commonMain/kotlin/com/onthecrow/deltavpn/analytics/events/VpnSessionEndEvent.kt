package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.SessionEndReason

/** `vpn_session_end` — a session that reached Connected tore down. Duration is coarse-bucketed. */
internal data class VpnSessionEndEvent(
    val reason: SessionEndReason,
    val durationMs: Long,
) : AnalyticsEvent {
    override val name = "vpn_session_end"
    override fun params() = mapOf(
        "reason" to reason.raw(),
        "duration_bucket" to sessionDurationBucket(durationMs),
    )
}
