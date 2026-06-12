package com.onthecrow.onthecrowvpn.connection

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.onthecrow.onthecrowvpn.connection.data.datastore.ConfigSourcesPreferencesDataSource
import com.onthecrow.onthecrowvpn.connection.data.datastore.migrateLegacyPreferences
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.ConfigSource
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class LegacyMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val legacyBundle = ConfigBundle(
        id = "abc",
        name = "sample",
        createdAt = 1L,
        updatedAt = 2L,
        configs = listOf(RemoteConfig(id = "c1", name = "Server", url = "vless://x")),
    )

    @Test
    fun migratesLegacyBundleIntoFirestoreSource() {
        val prefs = mutablePreferencesOf(
            ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_ID_KEY to "abc",
            ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_JSON_KEY to
                json.encodeToString(ConfigBundle.serializer(), legacyBundle),
            ConfigSourcesPreferencesDataSource.LEGACY_SELECTED_KEY to "c1",
        )

        migrateLegacyPreferences(prefs, json, now = 42L)

        val sources = json.decodeFromString(
            ListSerializer(ConfigSource.serializer()),
            prefs[ConfigSourcesPreferencesDataSource.SOURCES_KEY]!!,
        )
        assertEquals(1, sources.size)
        val source = sources.single() as ConfigSource.FirestoreSubscription
        assertEquals("abc", source.bundleId)
        assertEquals(42L, source.addedAt)
        // Legacy config ids must survive so the selection stays valid and the tunnel isn't restarted.
        assertEquals(legacyBundle, source.cachedBundle)

        assertEquals(source.id, prefs[ConfigSourcesPreferencesDataSource.SELECTED_SOURCE_KEY])
        assertEquals("c1", prefs[ConfigSourcesPreferencesDataSource.SELECTED_CONFIG_KEY])

        assertFalse(prefs.contains(ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_ID_KEY))
        assertFalse(prefs.contains(ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_JSON_KEY))
        assertFalse(prefs.contains(ConfigSourcesPreferencesDataSource.LEGACY_SELECTED_KEY))
    }

    @Test
    fun migrationIsIdempotent() {
        val prefs = mutablePreferencesOf(
            ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_ID_KEY to "abc",
            ConfigSourcesPreferencesDataSource.LEGACY_SELECTED_KEY to "c1",
        )

        migrateLegacyPreferences(prefs, json, now = 1L)
        val firstSources = prefs[ConfigSourcesPreferencesDataSource.SOURCES_KEY]
        val firstSelection = prefs[ConfigSourcesPreferencesDataSource.SELECTED_SOURCE_KEY]

        // A second run (e.g. a racing reader) must not duplicate or regenerate anything.
        migrateLegacyPreferences(prefs, json, now = 2L)
        assertEquals(firstSources, prefs[ConfigSourcesPreferencesDataSource.SOURCES_KEY])
        assertEquals(firstSelection, prefs[ConfigSourcesPreferencesDataSource.SELECTED_SOURCE_KEY])
    }

    @Test
    fun corruptLegacyBundleJsonStillMigratesTheId() {
        val prefs = mutablePreferencesOf(
            ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_ID_KEY to "abc",
            ConfigSourcesPreferencesDataSource.LEGACY_BUNDLE_JSON_KEY to "{not json",
        )

        migrateLegacyPreferences(prefs, json, now = 1L)

        val sources = json.decodeFromString(
            ListSerializer(ConfigSource.serializer()),
            prefs[ConfigSourcesPreferencesDataSource.SOURCES_KEY]!!,
        )
        val source = sources.single() as ConfigSource.FirestoreSubscription
        assertEquals("abc", source.bundleId)
        assertNull(source.cachedBundle)
    }

    @Test
    fun noLegacyDataIsNoOp() {
        val prefs = mutablePreferencesOf()

        migrateLegacyPreferences(prefs, json, now = 1L)

        assertFalse(prefs.contains(ConfigSourcesPreferencesDataSource.SOURCES_KEY))
        assertTrue(prefs.asMap().isEmpty())
    }
}
