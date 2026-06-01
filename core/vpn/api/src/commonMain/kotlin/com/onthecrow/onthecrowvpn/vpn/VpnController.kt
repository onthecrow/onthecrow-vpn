package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import kotlinx.coroutines.flow.StateFlow

interface VpnController {
    val status: StateFlow<ConnectionStatus>
    suspend fun connect(config: ValidatedConnectionConfig): ConnectResult
    suspend fun disconnect()
}
