package com.onthecrow.onthecrowvpn.connection.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class ConnectionConfigPreferencesDataSource(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    fun observeBundleId(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[BUNDLE_ID_KEY]?.takeIf { it.isNotBlank() }
    }

    suspend fun saveBundleId(id: String?) {
        dataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(BUNDLE_ID_KEY) else prefs[BUNDLE_ID_KEY] = id
        }
    }

    fun observeCachedBundle(): Flow<ConfigBundle?> = dataStore.data.map { prefs ->
        val raw = prefs[BUNDLE_JSON_KEY]?.takeIf { it.isNotBlank() } ?: return@map null
        runCatching { json.decodeFromString(ConfigBundle.serializer(), raw) }.getOrNull()
    }

    suspend fun saveCachedBundle(bundle: ConfigBundle?) {
        dataStore.edit { prefs ->
            if (bundle == null) {
                prefs.remove(BUNDLE_JSON_KEY)
            } else {
                prefs[BUNDLE_JSON_KEY] = json.encodeToString(ConfigBundle.serializer(), bundle)
            }
        }
    }

    fun observeSelectedConfigId(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[SELECTED_CONFIG_ID_KEY]?.takeIf { it.isNotBlank() }
    }

    suspend fun saveSelectedConfigId(id: String?) {
        dataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(SELECTED_CONFIG_ID_KEY) else prefs[SELECTED_CONFIG_ID_KEY] = id
        }
    }

    private companion object {
        val BUNDLE_ID_KEY = stringPreferencesKey("bundle_id")
        val BUNDLE_JSON_KEY = stringPreferencesKey("bundle_json")
        val SELECTED_CONFIG_ID_KEY = stringPreferencesKey("selected_config_id")
    }
}
