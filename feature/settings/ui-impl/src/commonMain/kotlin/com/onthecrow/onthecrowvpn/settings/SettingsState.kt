package com.onthecrow.onthecrowvpn.settings

import com.onthecrow.onthecrowvpn.uicore.State

internal data class SettingsState(
    val excludePushServices: Boolean = true,
) : State
