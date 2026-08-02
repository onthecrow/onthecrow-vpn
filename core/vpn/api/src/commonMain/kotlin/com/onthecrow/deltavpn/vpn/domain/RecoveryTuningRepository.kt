package com.onthecrow.deltavpn.vpn.domain

import kotlinx.coroutines.flow.Flow

/**
 * How impatient the tunnel-recovery ladder is allowed to be.
 *
 * Deliberately NOT part of `SplitTunnelSettings`: that model is in `VpnSyncWorker`'s `ConfigKey`, so
 * every change there tears the tunnel down and rebuilds it. This setting changes nothing about the tun
 * or the xray config — only how long the recovery ladder waits before condemning a quiet tunnel — so it
 * is applied live, on the next ladder run, without costing the user a reconnect.
 */
interface RecoveryTuningRepository {
    /** Emits the current value and every change to it. `false` (patient, the default) until changed. */
    fun observe(): Flow<Boolean>

    /** Persist the aggressive-keepalive preference. */
    suspend fun setAggressiveKeepalive(enabled: Boolean)
}
