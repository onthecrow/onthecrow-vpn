package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.QsBlockedReason
import com.onthecrow.onthecrowvpn.analytics.QsTileAction

/** `qs_tile_action` — a Quick-Settings tile action. [blockedReason] is `NONE` when not blocked. */
internal data class QsTileActionEvent(
    val action: QsTileAction,
    val blockedReason: QsBlockedReason,
) : AnalyticsEvent {
    override val name = "qs_tile_action"
    override fun params() = mapOf(
        "action" to action.raw(),
        "blocked_reason" to blockedReason.raw(),
    )
}
