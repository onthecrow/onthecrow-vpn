package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.SourceKind

/** `source_deleted` — a source was removed. [wasActive] = the delete forced a live disconnect. */
internal data class SourceDeletedEvent(
    val kind: SourceKind,
    val wasActive: Boolean,
) : AnalyticsEvent {
    override val name = "source_deleted"
    override fun params() = mapOf(
        "kind" to kind.raw(),
        "was_active" to wasActive.toString(),
    )
}
