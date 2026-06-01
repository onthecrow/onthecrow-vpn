package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import com.onthecrow.onthecrowvpn.uicore.Event
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal sealed interface ConnectionEvent : Event {
    data class OnInputChanged(val value: String) : ConnectionEvent
    data object OnApplyClick : ConnectionEvent
    data object OnConnectClick : ConnectionEvent
    data object OnDisconnectClick : ConnectionEvent
    data object OnSnackbarShown : ConnectionEvent
    data class OnValidationStarted(val rawConfig: String) : ConnectionEvent
    data class OnValidationSucceeded(val config: ValidatedConnectionConfig) : ConnectionEvent
    data class OnValidationFailed(val message: String) : ConnectionEvent
    data class OnConnectionStatusChanged(val status: ConnectionStatus) : ConnectionEvent
    data class OnSnackbarRequested(val message: String) : ConnectionEvent
}
