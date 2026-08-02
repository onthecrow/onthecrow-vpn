package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.SourceKind

/** `source_added` — a configuration source was added. Activation step 1. */
internal data class SourceAddedEvent(
    val kind: SourceKind,
    val sourceCount: Int,
) : AnalyticsEvent {
    override val name = "source_added"
    override fun params() = mapOf(
        "kind" to kind.raw(),
        "source_count_bucket" to sourceCountBucket(sourceCount),
    )
}
