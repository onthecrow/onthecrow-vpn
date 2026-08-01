package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.RecoveryMode
import com.onthecrow.onthecrowvpn.analytics.RecoveryOutcome
import com.onthecrow.onthecrowvpn.analytics.RecoveryTrigger
import com.onthecrow.onthecrowvpn.analytics.Transport

/**
 * `vpn_recovery` — one recovery-ladder run's outcome (never per keepalive probe). [transport] is the
 * only network detail permitted, and only as a coarse type.
 */
internal data class VpnRecoveryEvent(
    val trigger: RecoveryTrigger,
    val outcome: RecoveryOutcome,
    val attempts: Int,
    val durationMs: Long,
    val transport: Transport?,
    val mode: RecoveryMode,
) : AnalyticsEvent {
    override val name = "vpn_recovery"
    override fun params(): Map<String, String> = buildMap {
        put("trigger", trigger.raw())
        put("outcome", outcome.raw())
        put("attempts_bucket", attemptsBucket(attempts))
        put("duration_bucket", shortDurationBucket(durationMs))
        put("mode", mode.raw())
        transport?.let { put("transport", it.raw()) }
    }
}
