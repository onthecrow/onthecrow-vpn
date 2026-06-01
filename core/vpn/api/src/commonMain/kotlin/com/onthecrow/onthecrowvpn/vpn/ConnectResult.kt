package com.onthecrow.onthecrowvpn.vpn

sealed interface ConnectResult {
    data object Started : ConnectResult
    data class Failed(val message: String) : ConnectResult
}
