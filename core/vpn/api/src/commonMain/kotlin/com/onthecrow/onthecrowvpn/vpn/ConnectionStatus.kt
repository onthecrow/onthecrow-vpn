package com.onthecrow.onthecrowvpn.vpn

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object PreparingPermission : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data object Disconnecting : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}
