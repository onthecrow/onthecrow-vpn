package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.AddFailureReason
import com.onthecrow.onthecrowvpn.analytics.SourceKind

/** `source_add_failed` — adding a source failed. [reason] is a bounded category, never the message. */
internal data class SourceAddFailedEvent(
    val kind: SourceKind,
    val reason: AddFailureReason,
) : AnalyticsEvent {
    override val name = "source_add_failed"
    override fun params() = mapOf(
        "kind" to kind.raw(),
        "reason" to reason.raw(),
    )
}
