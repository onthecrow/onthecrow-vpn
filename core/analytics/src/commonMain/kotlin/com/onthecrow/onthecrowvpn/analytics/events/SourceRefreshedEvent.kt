package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.RefreshFailureReason
import com.onthecrow.onthecrowvpn.analytics.SourceKind

/**
 * `source_refreshed` — a subscription source was re-fetched. [failureReason] is `null` on success;
 * non-null adds `result=failed` plus the bounded reason.
 */
internal data class SourceRefreshedEvent(
    val kind: SourceKind,
    val failureReason: RefreshFailureReason?,
) : AnalyticsEvent {
    override val name = "source_refreshed"
    override fun params(): Map<String, String> = buildMap {
        put("kind", kind.raw())
        put("result", if (failureReason == null) "ok" else "failed")
        failureReason?.let { put("reason", it.raw()) }
    }
}
