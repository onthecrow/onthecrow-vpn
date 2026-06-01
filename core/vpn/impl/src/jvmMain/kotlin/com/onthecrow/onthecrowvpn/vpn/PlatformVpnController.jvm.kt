package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class PlatformVpnController : VpnController {
    private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = mutableStatus

    override suspend fun connect(config: ValidatedConnectionConfig): ConnectResult {
        val message = "VPN is not implemented on this platform yet"
        mutableStatus.value = ConnectionStatus.Error(message)
        return ConnectResult.Failed(message)
    }

    override suspend fun disconnect() {
        mutableStatus.value = ConnectionStatus.Disconnected
    }
}
