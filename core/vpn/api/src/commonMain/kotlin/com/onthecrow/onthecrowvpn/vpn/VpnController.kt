package com.onthecrow.onthecrowvpn.vpn

import kotlinx.coroutines.flow.StateFlow

interface VpnController {
    val status: StateFlow<ConnectionStatus>
    suspend fun connect(xrayJson: String): ConnectResult
    suspend fun disconnect()
}
