package com.onthecrow.onthecrowvpn.connection.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class ConnectionConfigPreferencesDataSource(
    private val dataStore: DataStore<Preferences>,
) {
    fun observeRawConfig(): Flow<String?> {
        return dataStore.data.map { preferences ->
            preferences[RAW_CONFIG_KEY]?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun saveRawConfig(rawConfig: String) {
        dataStore.edit { preferences ->
            preferences[RAW_CONFIG_KEY] = rawConfig
        }
    }

    private companion object {
        val RAW_CONFIG_KEY = stringPreferencesKey("raw_config")
    }
}
