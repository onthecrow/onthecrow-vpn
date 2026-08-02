package com.onthecrow.deltavpn.connection.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.onthecrow.deltavpn.vpn.domain.RecoveryTuningRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [RecoveryTuningRepository]. Shares the `vpn_settings` store with the consent and
 * split-tunnel settings — all per-install VPN preferences that survive process death.
 *
 * Absent key reads as `false`, so an install that predates this setting gets the patient (current)
 * ladder, which is the intended default.
 */
internal class RecoveryTuningRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : RecoveryTuningRepository {

    override fun observe(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AGGRESSIVE_KEEPALIVE_KEY] == true
    }

    override suspend fun setAggressiveKeepalive(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AGGRESSIVE_KEEPALIVE_KEY] = enabled }
    }

    private companion object {
        val AGGRESSIVE_KEEPALIVE_KEY = booleanPreferencesKey("aggressive_keepalive")
    }
}
