package com.onthecrow.onthecrowvpn.settings

import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SettingsReducerTest {
    private val reducer = SettingsReducer()

    @Test
    fun loadedReflectsPersistedValue() = runTest {
        val state = reducer.reduce(
            SettingsState(excludePushServices = true),
            SettingsEvent.OnSettingsLoaded(
                excludePushServices = false,
                splitTunnelMode = SplitTunnelMode.OFF,
                splitTunnelCount = 0,
            ),
        )
        assertFalse(state.excludePushServices)
    }

    @Test
    fun toggleIsOptimistic() = runTest {
        val state = reducer.reduce(
            SettingsState(excludePushServices = false),
            SettingsEvent.OnExcludePushChanged(enabled = true),
        )
        assertTrue(state.excludePushServices)
    }
}
