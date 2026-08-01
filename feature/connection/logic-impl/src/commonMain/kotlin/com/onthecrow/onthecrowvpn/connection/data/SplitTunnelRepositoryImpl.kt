package com.onthecrow.onthecrowvpn.connection.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onthecrow.onthecrowvpn.errorreporting.ErrorDomain
import com.onthecrow.onthecrowvpn.errorreporting.ErrorReporter
import com.onthecrow.onthecrowvpn.vpn.domain.SplitTunnelRepository
import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * DataStore-backed [SplitTunnelRepository]. Lives here (not in core/vpn/impl) because core/vpn targets
 * macOS, where DataStore/core-coroutines have no targets; this module is android/ios/jvm only.
 */
internal class SplitTunnelRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val errorReporter: ErrorReporter,
) : SplitTunnelRepository {
    private val writeMutex = Mutex()

    override fun observe(): Flow<SplitTunnelSettings> = dataStore.data.map { prefs ->
        decode(prefs[SETTINGS_KEY])
    }

    override suspend fun update(transform: (SplitTunnelSettings) -> SplitTunnelSettings) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val updated = transform(decode(prefs[SETTINGS_KEY]))
                prefs[SETTINGS_KEY] = json.encodeToString(SplitTunnelSettings.serializer(), updated)
            }
        }
    }

    private fun decode(raw: String?): SplitTunnelSettings {
        if (raw.isNullOrBlank()) return SplitTunnelSettings()
        return runCatching { json.decodeFromString(SplitTunnelSettings.serializer(), raw) }
            .getOrElse {
                // Persisted routing failed to parse — split-tunnel silently resets to defaults.
                errorReporter.report(ErrorDomain.DATASTORE, it)
                SplitTunnelSettings()
            }
    }

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("split_tunnel_json")
    }
}
