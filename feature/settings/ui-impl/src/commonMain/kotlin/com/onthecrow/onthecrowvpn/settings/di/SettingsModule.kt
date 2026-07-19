package com.onthecrow.onthecrowvpn.settings.di

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onthecrow.onthecrowvpn.navigation.registerScreen
import com.onthecrow.onthecrowvpn.settings.SettingsDestination
import com.onthecrow.onthecrowvpn.settings.SettingsReducer
import com.onthecrow.onthecrowvpn.settings.SettingsScreen
import com.onthecrow.onthecrowvpn.settings.SettingsViewModel
import com.onthecrow.onthecrowvpn.settings.SplitTunnelDestination
import com.onthecrow.onthecrowvpn.settings.SplitTunnelReducer
import com.onthecrow.onthecrowvpn.settings.SplitTunnelScreen
import com.onthecrow.onthecrowvpn.settings.SplitTunnelViewModel
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
