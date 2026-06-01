package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import com.onthecrow.onthecrowvpn.uicore.State
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal data class ConnectionState(
    val rawInput: String = "",
    val validatedConfig: ValidatedConnectionConfig? = null,
    val validationError: String? = null,
    val isValidating: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val snackbarMessage: String? = null,
) : State {
    val canApply: Boolean get() = rawInput.isNotBlank() && !isValidating
    val canConnect: Boolean get() = validatedConfig != null && !isBusy
    val isConnected: Boolean get() = connectionStatus is ConnectionStatus.Connected
    val isBusy: Boolean
        get() = isValidating ||
                connectionStatus is ConnectionStatus.PreparingPermission ||
                connectionStatus is ConnectionStatus.Connecting ||
                connectionStatus is ConnectionStatus.Disconnecting
}
