package com.onthecrow.onthecrowvpn.connection.domain

import kotlinx.coroutines.flow.Flow

/**
 * Whether the user has seen the prominent VPN disclosure and given affirmative consent.
 *
 * Google Play's VpnService policy requires the disclosure — device-level tunnel, user-supplied server,
 * local-only config, diagnostics to Firebase — to be shown in the normal usage flow and accepted
 * BEFORE the system `VpnService.prepare()` dialog. The acceptance is persisted so the step is shown
 * once (on the first connect) rather than every time.
 */
interface VpnConsentRepository {
    /** Emits the current grant state and every change to it. `false` until the user accepts. */
    fun observe(): Flow<Boolean>

    /** Records that the user accepted the disclosure. Idempotent. */
    suspend fun grant()
}
