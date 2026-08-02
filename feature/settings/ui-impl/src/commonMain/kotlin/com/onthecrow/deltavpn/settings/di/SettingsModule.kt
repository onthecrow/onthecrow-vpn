package com.onthecrow.deltavpn.settings.di

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onthecrow.deltavpn.navigation.registerScreen
import com.onthecrow.deltavpn.settings.SettingsDestination
import com.onthecrow.deltavpn.settings.SettingsReducer
import com.onthecrow.deltavpn.settings.SettingsScreen
import com.onthecrow.deltavpn.settings.SettingsViewModel
import com.onthecrow.deltavpn.settings.SplitTunnelDestination
import com.onthecrow.deltavpn.settings.SplitTunnelReducer
import com.onthecrow.deltavpn.settings.SplitTunnelScreen
import com.onthecrow.deltavpn.settings.SplitTunnelViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsModule = module {
    single { SettingsReducer() }
    viewModelOf(::SettingsViewModel)
    single { SplitTunnelReducer() }
    viewModelOf(::SplitTunnelViewModel)

    registerScreen<SettingsDestination> { _, modifier ->
        val viewModel: SettingsViewModel = koinViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SettingsScreen(
            state = state,
            modifier = modifier,
            onEvent = viewModel::onEvent,
        )
    }

    registerScreen<SplitTunnelDestination> { _, modifier ->
        val viewModel: SplitTunnelViewModel = koinViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SplitTunnelScreen(
            state = state,
            modifier = modifier,
            onEvent = viewModel::onEvent,
        )
    }
}
