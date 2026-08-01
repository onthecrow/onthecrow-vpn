package com.onthecrow.onthecrowvpn.analytics.events

/** `settings_push_bypass_toggled` — the "allow notifications under VPN" switch changed. Boolean only. */
internal data class SettingsPushBypassToggledEvent(
    val enabled: Boolean,
) : AnalyticsEvent {
    override val name = "settings_push_bypass_toggled"
    override fun params() = mapOf("enabled" to enabled.toString())
}
