package com.onthecrow.onthecrowvpn.vpn

import kotlinx.coroutines.flow.StateFlow

interface VpnController {
    val status: StateFlow<ConnectionStatus>
    suspend fun connect(xrayJson: String): ConnectResult
    suspend fun disconnect()

    /**
     * Stops the tunnel AND forgets any persisted system VPN profile, so the configuration disappears
     * from the OS (System Settings) entirely. Used when the bundle is revoked remotely. Default is a
     * plain [disconnect]; platforms with a persisted profile (apple) override to also remove it.
     */
    suspend fun revoke() = disconnect()

    /**
     * Optional, non-blocking advisory to surface to the user when they connect — e.g. a system proxy
     * that may route traffic around this full-tunnel VPN. Returns `null` when there's nothing to say.
     * Default is `null`; only platforms that need it (Windows) override this.
     */
    fun connectNotice(): String? = null
}
