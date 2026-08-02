package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.ConfigInvalidReason

/**
 * `connect_config_validation` — the selected config was re-validated at connect time. [reason] is
 * `null` for a valid config; non-null adds `result=invalid` plus the bounded reason.
 */
internal data class ConnectConfigValidationEvent(
    val reason: ConfigInvalidReason?,
) : AnalyticsEvent {
    override val name = "connect_config_validation"
    override fun params(): Map<String, String> = buildMap {
        put("result", if (reason == null) "valid" else "invalid")
        reason?.let { put("reason", it.raw()) }
    }
}
