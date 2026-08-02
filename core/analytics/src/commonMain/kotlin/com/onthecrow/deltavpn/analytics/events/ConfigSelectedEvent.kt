package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.SourceKind

/** `config_selected` — the active configuration was chosen. [isSwitch] = a different one was selected before. */
internal data class ConfigSelectedEvent(
    val sourceKind: SourceKind,
    val isSwitch: Boolean,
) : AnalyticsEvent {
    override val name = "config_selected"
    override fun params() = mapOf(
        "source_kind" to sourceKind.raw(),
        "is_switch" to isSwitch.toString(),
    )
}
