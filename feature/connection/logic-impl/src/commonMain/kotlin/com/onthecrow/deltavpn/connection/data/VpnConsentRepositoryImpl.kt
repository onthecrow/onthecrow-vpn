package com.onthecrow.deltavpn.connection.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.onthecrow.deltavpn.connection.domain.VpnConsentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [VpnConsentRepository]. Shares the `vpn_settings` store with the split-tunnel
 * settings — both are per-install VPN preferences that survive process death and outlive any single
 * connection.
 */
internal class VpnConsentRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : VpnConsentRepository {

    override fun observe(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[GRANTED_KEY] == true
    }

    override suspend fun grant() {
        dataStore.edit { prefs -> prefs[GRANTED_KEY] = true }
    }

    private companion object {
        val GRANTED_KEY = booleanPreferencesKey("vpn_consent_granted")
    }
}
