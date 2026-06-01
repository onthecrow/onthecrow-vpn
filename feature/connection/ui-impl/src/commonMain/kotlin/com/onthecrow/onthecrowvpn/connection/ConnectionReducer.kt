package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.uicore.Reducer
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal class ConnectionReducer : Reducer<ConnectionState, ConnectionEvent> {
    override suspend fun reduce(
        state: ConnectionState,
        event: ConnectionEvent,
    ): ConnectionState {
        return when (event) {
            is ConnectionEvent.OnInputChanged -> state.copy(
                rawInput = event.value,
                validatedConfig = null,
                validationError = null,
            )

            ConnectionEvent.OnApplyClick,
            ConnectionEvent.OnConnectClick,
            ConnectionEvent.OnDisconnectClick -> state

            is ConnectionEvent.OnValidationStarted -> state.copy(
                rawInput = event.rawConfig,
                isValidating = true,
                validationError = null,
            )

            is ConnectionEvent.OnValidationSucceeded -> state.copy(
                rawInput = event.config.rawConfig,
                validatedConfig = event.config,
                validationError = null,
                isValidating = false,
            )

            is ConnectionEvent.OnValidationFailed -> state.copy(
                validatedConfig = null,
                validationError = event.message,
                isValidating = false,
            )

            is ConnectionEvent.OnConnectionStatusChanged -> state.copy(
                connectionStatus = event.status,
                snackbarMessage = when (val status = event.status) {
                    is ConnectionStatus.Error -> status.message
                    else -> state.snackbarMessage
                },
            )

            is ConnectionEvent.OnSnackbarRequested -> state.copy(snackbarMessage = event.message)

            ConnectionEvent.OnSnackbarShown -> state.copy(snackbarMessage = null)
        }
    }
}
