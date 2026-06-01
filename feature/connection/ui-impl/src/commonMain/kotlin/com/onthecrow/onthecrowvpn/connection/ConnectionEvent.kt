package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ActiveBundleState
import com.onthecrow.onthecrowvpn.uicore.Event
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal sealed interface ConnectionEvent : Event {
    // User actions
    data class OnIdInputChanged(val value: String) : ConnectionEvent
    data object OnLoadClick : ConnectionEvent
    data object OnEditIdClick : ConnectionEvent
    data class OnConfigSelected(val configId: String) : ConnectionEvent
    data object OnConnectClick : ConnectionEvent
    data object OnDisconnectClick : ConnectionEvent
    data object OnSnackbarShown : ConnectionEvent

    // Internal events
    data class OnActiveBundleChanged(val state: ActiveBundleState) : ConnectionEvent
    data class OnConnectionStatusChanged(val status: ConnectionStatus) : ConnectionEvent
    data class OnSnackbarRequested(val message: String) : ConnectionEvent
    data object OnLoadStarted : ConnectionEvent
}
