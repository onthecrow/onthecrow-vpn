package com.onthecrow.onthecrowvpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow

internal object AndroidVpnRuntime {
    val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
}
