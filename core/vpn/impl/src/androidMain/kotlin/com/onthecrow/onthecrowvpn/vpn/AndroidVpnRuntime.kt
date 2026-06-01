package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import kotlinx.coroutines.flow.MutableStateFlow

internal object AndroidVpnRuntime {
    val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)

    @Volatile
    var pendingConfig: ValidatedConnectionConfig? = null
}
