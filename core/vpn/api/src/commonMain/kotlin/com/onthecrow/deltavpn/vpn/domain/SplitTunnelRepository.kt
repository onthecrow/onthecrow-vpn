package com.onthecrow.deltavpn.vpn.domain

import com.onthecrow.deltavpn.vpn.model.SplitTunnelSettings
import kotlinx.coroutines.flow.Flow

/** Persisted per-app VPN routing settings. */
interface SplitTunnelRepository {
    fun observe(): Flow<SplitTunnelSettings>
    suspend fun update(transform: (SplitTunnelSettings) -> SplitTunnelSettings)
}
