package com.onthecrow.onthecrowvpn.analytics.events

/** `settings_aggressive_keepalive_toggled` — the "aggressive keepalive" switch changed. Boolean only. */
internal data class SettingsAggressiveKeepaliveToggledEvent(
    val enabled: Boolean,
) : AnalyticsEvent {
    override val name = "settings_aggressive_keepalive_toggled"
    override fun params() = mapOf("enabled" to enabled.toString())
}
