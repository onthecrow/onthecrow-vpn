package com.onthecrow.onthecrowvpn.vpn

import kotlinx.coroutines.flow.StateFlow

interface VpnController {
    val status: StateFlow<ConnectionStatus>
    suspend fun connect(xrayJson: String): ConnectResult
    suspend fun disconnect()

    /**
     * Optional, non-blocking advisory to surface to the user when they connect — e.g. a system proxy
     * that may route traffic around this full-tunnel VPN. Returns `null` when there's nothing to say.
     * Default is `null`; only platforms that need it (Windows) override this.
     */
    fun connectNotice(): String? = null
}
