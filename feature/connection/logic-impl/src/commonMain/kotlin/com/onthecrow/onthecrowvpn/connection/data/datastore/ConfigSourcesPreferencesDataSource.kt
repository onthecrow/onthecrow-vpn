package com.onthecrow.onthecrowvpn.connection.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Persists the multi-source config list and the global selection. Lives in the SAME preferences file
 * as the legacy single-bundle keys so [migrateLegacyIfNeeded] can convert them in place.
 *
 * The whole source list is one atomic JSON blob ([SOURCES_KEY]); every mutation is a single
 * `dataStore.edit` guarded by a [Mutex] (orchestrator cache writes can race user add/remove).
 */
internal class ConfigSourcesPreferencesDataSource(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    private val writeMutex = Mutex()

    fun observeSources(): Flow<List<ConfigSource>> = dataStore.data.map { prefs ->
        decodeSources(prefs[SOURCES_KEY])
    }

    suspend fun addSource(source: ConfigSource) = editSources { sources -> sources + source }

    suspend fun removeSources(sourceIds: Collection<String>) = editSources { sources ->
        sources.filterNot { it.id in sourceIds }
    }

    suspend fun updateSource(sourceId: String, transform: (ConfigSource) -> ConfigSource) {
        // No-op when the source vanished (e.g. deleted mid-refresh).
        editSources { sources -> sources.map { if (it.id == sourceId) transform(it) else it } }
    }

    fun observeSelection(): Flow<ConfigRef?> = dataStore.data.map { prefs ->
        val sourceId = prefs[SELECTED_SOURCE_KEY]?.takeIf { it.isNotBlank() } ?: return@map null
        val configId = prefs[SELECTED_CONFIG_KEY]?.takeIf { it.isNotBlank() } ?: return@map null
        ConfigRef(sourceId, configId)
    }

    suspend fun setSelection(ref: ConfigRef?) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                if (ref == null) {
                    prefs.remove(SELECTED_SOURCE_KEY)
                    prefs.remove(SELECTED_CONFIG_KEY)
                } else {
                    prefs[SELECTED_SOURCE_KEY] = ref.sourceId
                    prefs[SELECTED_CONFIG_KEY] = ref.configId
                }
            }
        }
    }

    fun observeCollapsedGroups(): Flow<Set<String>> = dataStore.data.map { prefs ->
        decodeCollapsed(prefs[COLLAPSED_GROUPS_KEY])
    }

    suspend fun setGroupCollapsed(groupKey: String, collapsed: Boolean) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeCollapsed(prefs[COLLAPSED_GROUPS_KEY])
                val updated = if (collapsed) current + groupKey else current - groupKey
                prefs[COLLAPSED_GROUPS_KEY] = json.encodeToString(STRING_LIST_SERIALIZER, updated.toList())
            }
        }
    }

    private fun decodeCollapsed(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching { json.decodeFromString(STRING_LIST_SERIALIZER, raw).toSet() }.getOrElse { emptySet() }
    }

    suspend fun migrateLegacyIfNeeded(now: Long) {
        writeMutex.withLock {
            dataStore.edit { prefs -> migrateLegacyPreferences(prefs, json, now) }
        }
    }

    private suspend fun editSources(transform: (List<ConfigSource>) -> List<ConfigSource>) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeSources(prefs[SOURCES_KEY])
                prefs[SOURCES_KEY] = json.encodeToString(SOURCES_SERIALIZER, transform(current))
            }
        }
    }

    private fun decodeSources(raw: String?): List<ConfigSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(SOURCES_SERIALIZER, raw) }.getOrElse { emptyList() }
    }

    companion object {
        private val SOURCES_SERIALIZER = ListSerializer(ConfigSource.serializer())
        private val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())

        val SOURCES_KEY = stringPreferencesKey("sources_json")
        val COLLAPSED_GROUPS_KEY = stringPreferencesKey("collapsed_groups")
        val SELECTED_SOURCE_KEY = stringPreferencesKey("selected_source_id")
        // New name: the legacy "selected_config_id" is consumed (and removed) by the migration.
        val SELECTED_CONFIG_KEY = stringPreferencesKey("selected_config_id_v2")

        // Legacy single-bundle keys (pre multi-source); only the migration knows about them.
        val LEGACY_BUNDLE_ID_KEY = stringPreferencesKey("bundle_id")
        val LEGACY_BUNDLE_JSON_KEY = stringPreferencesKey("bundle_json")
        val LEGACY_SELECTED_KEY = stringPreferencesKey("selected_config_id")
    }
}

/**
 * Converts the legacy single-bundle persistence into one FirestoreSubscription source. Pure over
 * [prefs] (testable), idempotent: a second call is a no-op because [ConfigSourcesPreferencesDataSource.SOURCES_KEY]
 * exists (or there was nothing to migrate and the legacy keys are gone). Keeps the legacy
 * [com.onthecrow.onthecrowvpn.connection.model.RemoteConfig.id]s (inside the cached bundle), so the
 * migrated selection stays valid and a connected tunnel is not restarted after the app update.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun migrateLegacyPreferences(prefs: MutablePreferences, json: Json, now: Long) {
    if (prefs.contains(ConfigSourcesPreferencesDataSource.SOURCES_KEY)) return
    val legacyId = prefs[ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_ID_KEY]?.takeIf { it.isNotBlank() }
    if (legacyId != null) {
        val cached = prefs[ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_JSON_KEY]
            ?.let { raw -> runCatching { json.decodeFromString(ConfigBundle.serializer(), raw) }.getOrNull() }
        val source = ConfigSource.FirestoreSubscription(
            id = Uuid.random().toString(),
            addedAt = now,
            bundleId = legacyId,
            cachedBundle = cached,
        )
        prefs[ConfigSourcesPreferencesDataSource.SOURCES_KEY] =
            json.encodeToString(ListSerializer(ConfigSource.serializer()), listOf(source))
        prefs[ConfigSourcesPreferencesDataSource.LEGACY_SELECTED_KEY]?.takeIf { it.isNotBlank() }?.let { selected ->
            prefs[ConfigSourcesPreferencesDataSource.SELECTED_SOURCE_KEY] = source.id
            prefs[ConfigSourcesPreferencesDataSource.SELECTED_CONFIG_KEY] = selected
        }
    }
    prefs.remove(ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_ID_KEY)
    prefs.remove(ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_JSON_KEY)
    prefs.remove(ConfigSourcesPreferencesDataSource.LEGACY_SELECTED_KEY)
}
