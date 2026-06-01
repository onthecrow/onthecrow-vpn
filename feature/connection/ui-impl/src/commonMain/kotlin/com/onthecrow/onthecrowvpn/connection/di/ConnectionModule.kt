package com.onthecrow.onthecrowvpn.connection.di

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onthecrow.onthecrowvpn.connection.ConnectionDestination
import com.onthecrow.onthecrowvpn.connection.ConnectionReducer
import com.onthecrow.onthecrowvpn.connection.ConnectionScreen
import com.onthecrow.onthecrowvpn.connection.ConnectionViewModel
import com.onthecrow.onthecrowvpn.navigation.registerScreen
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
