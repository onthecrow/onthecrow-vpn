package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.AutoRestartReason
import com.onthecrow.onthecrowvpn.analytics.AutoRestartResult

/** `tunnel_auto_restart` — a live tunnel re-established because a setting changed (not a failure). */
internal data class TunnelAutoRestartEvent(
    val reason: AutoRestartReason,
    val result: AutoRestartResult,
) : AnalyticsEvent {
    override val name = "tunnel_auto_restart"
    override fun params() = mapOf(
        "reason" to reason.raw(),
        "result" to result.raw(),
    )
}
