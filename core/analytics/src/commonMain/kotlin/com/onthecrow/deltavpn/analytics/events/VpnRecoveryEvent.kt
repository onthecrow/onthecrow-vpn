package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.RecoveryMode
import com.onthecrow.deltavpn.analytics.RecoveryOutcome
import com.onthecrow.deltavpn.analytics.RecoveryTrigger
import com.onthecrow.deltavpn.analytics.Transport

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
