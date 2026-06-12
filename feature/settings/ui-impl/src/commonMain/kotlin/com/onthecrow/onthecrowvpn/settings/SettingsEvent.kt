package com.onthecrow.onthecrowvpn.settings

import com.onthecrow.onthecrowvpn.uicore.Event

internal sealed interface SettingsEvent : Event {
    data class OnExcludePushChanged(val enabled: Boolean) : SettingsEvent
    data object OnBackClick : SettingsEvent

    // Internal
    data class OnSettingsLoaded(val excludePushServices: Boolean) : SettingsEvent
}
