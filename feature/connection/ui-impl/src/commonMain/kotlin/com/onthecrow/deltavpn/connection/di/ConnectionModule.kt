package com.onthecrow.deltavpn.connection.di

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onthecrow.deltavpn.connection.ConnectionDestination
import com.onthecrow.deltavpn.connection.ConnectionReducer
import com.onthecrow.deltavpn.connection.ConnectionScreen
import com.onthecrow.deltavpn.connection.ConnectionViewModel
import com.onthecrow.deltavpn.navigation.registerScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val connectionModule = module {
    single { ConnectionReducer() }
    viewModelOf(::ConnectionViewModel)

    registerScreen<ConnectionDestination> { _, modifier ->
        val viewModel: ConnectionViewModel = koinViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        ConnectionScreen(
            state = state,
            modifier = modifier,
            onEvent = viewModel::onEvent,
        )
    }
}
