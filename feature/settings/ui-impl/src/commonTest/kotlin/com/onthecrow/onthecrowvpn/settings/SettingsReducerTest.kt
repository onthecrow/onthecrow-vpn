package com.onthecrow.onthecrowvpn.settings

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
            SettingsEvent.OnSettingsLoaded(excludePushServices = false),
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
