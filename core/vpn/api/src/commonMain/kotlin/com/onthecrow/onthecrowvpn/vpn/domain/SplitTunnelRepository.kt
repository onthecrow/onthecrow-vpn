package com.onthecrow.onthecrowvpn.vpn.domain

import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelSettings
import kotlinx.coroutines.flow.Flow

/** Persisted per-app VPN routing settings. */
interface SplitTunnelRepository {
    fun observe(): Flow<SplitTunnelSettings>
    suspend fun update(transform: (SplitTunnelSettings) -> SplitTunnelSettings)
}
