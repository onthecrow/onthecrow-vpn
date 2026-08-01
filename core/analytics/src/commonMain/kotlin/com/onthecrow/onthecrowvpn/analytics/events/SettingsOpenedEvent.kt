package com.onthecrow.onthecrowvpn.analytics.events

/** `settings_opened` — the settings screen was opened (top of the configuration-feature funnel). */
internal class SettingsOpenedEvent : AnalyticsEvent {
    override val name = "settings_opened"
    override fun params(): Map<String, String> = emptyMap()
}
