package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigSource
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.coroutines.ApplicationScopeProvider
import com.onthecrow.onthecrowvpn.firebase.BundleResult
import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration-style checks of the multi-source pipeline: user selection of URL/link configs must
 * round-trip through the repository and come back as the effective selection (not be overridden by
 * auto-pick), and revocation must only clear the selection when it lived in the revoked source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ConfigSourcesOrchestratorTest {

    @Test
    fun selectingUrlConfigSurvivesAutoPick() = runTest {
        val env = Env(this, sources = listOf(firestoreSource, urlSource, linkSource))

        // Initial state: nothing selected → auto-pick takes the FIRST firestore config.
        val initial = env.orchestrator.state.filterNotNull().first { it.groups.isNotEmpty() }
        assertEquals(ConfigRef("fs1", "f-c1"), initial.selected)

        // User selects a config from the subscription-URL group.
        env.repository.setSelection(ConfigRef("url1", "u-c2"))
        val afterUrl = env.orchestrator.state.first { it.selected == ConfigRef("url1", "u-c2") }
        assertEquals("hysteria2://u2", afterUrl.selectedConfig?.url)

        // User selects an individual xray link (per-link source id).
        env.repository.setSelection(ConfigRef("link1", "l-c1"))
        val afterLink = env.orchestrator.state.first { it.selected == ConfigRef("link1", "l-c1") }
        assertEquals("hysteria2://l1", afterLink.selectedConfig?.url)
    }

    @Test
    fun revocationOfOtherSourceKeepsUrlSelection() = runTest {
        val env = Env(this, sources = listOf(firestoreSource, urlSource))
        env.orchestrator.state.first { it.groups.isNotEmpty() }
        env.repository.setSelection(ConfigRef("url1", "u-c1"))
        env.orchestrator.state.first { it.selected == ConfigRef("url1", "u-c1") }

        // The firestore bundle is deleted remotely — its group goes, but the URL selection stays.
        env.firestore.results.update { it + ("bundle-1" to BundleResult.NotFound) }
        val after = env.orchestrator.state.first { st -> st.groups.none { it.sourceId == "fs1" } }
        assertEquals(ConfigRef("url1", "u-c1"), after.selected)
    }

    private class Env(testScope: TestScope, sources: List<ConfigSource>) {
        val repository = FakeRepository(sources)
        val firestore = FakeFirestoreClient()
        val orchestrator = ConfigSourcesOrchestrator(
            repository = repository,
            firestoreClient = firestore,
            scopeProvider = object : ApplicationScopeProvider {
                override val scope = CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler))
            },
        )
    }

    private class FakeRepository(initial: List<ConfigSource>) : ConfigSourcesRepository {
        private val sources = MutableStateFlow(initial)
        private val selection = MutableStateFlow<ConfigRef?>(null)

        override fun observeSources(): Flow<List<ConfigSource>> = sources
        override suspend fun addSource(source: ConfigSource) = sources.update { it + source }
        override suspend fun removeSources(sourceIds: Collection<String>) =
            sources.update { list -> list.filterNot { it.id in sourceIds } }

        override suspend fun updateSource(sourceId: String, transform: (ConfigSource) -> ConfigSource) =
            sources.update { list -> list.map { if (it.id == sourceId) transform(it) else it } }

        override fun observeSelection(): Flow<ConfigRef?> = selection
        override suspend fun setSelection(ref: ConfigRef?) {
            selection.value = ref
        }

        override suspend fun migrateLegacyIfNeeded() = Unit
    }

    private class FakeFirestoreClient : FirestoreClient {
        val results = MutableStateFlow<Map<String, BundleResult>>(emptyMap())
        override val isAvailable: Boolean = true
        override fun observeBundle(bundleId: String): Flow<BundleResult> =
            results.filterNotNull().run {
                kotlinx.coroutines.flow.flow {
                    collect { map -> map[bundleId]?.let { emit(it) } }
                }
            }
    }

    private companion object {
        val firestoreSource = ConfigSource.FirestoreSubscription(
            id = "fs1",
            addedAt = 1,
            bundleId = "bundle-1",
            cachedBundle = ConfigBundle(
                id = "bundle-1",
                name = "FS",
                createdAt = 0,
                updatedAt = 0,
                configs = listOf(
                    RemoteConfig(id = "f-c1", name = "Russia #1", url = "vless://f1"),
                    RemoteConfig(id = "f-c2", name = "Russia #2", url = "hysteria2://f2"),
                ),
            ),
        )

        val urlSource = ConfigSource.SubscriptionUrl(
            id = "url1",
            addedAt = 2,
            url = "https://example.com:2096/sub/abc",
            title = "Example",
            configs = listOf(
                RemoteConfig(id = "u-c1", name = "fi-1", url = "vless://u1"),
                RemoteConfig(id = "u-c2", name = "fi-2", url = "hysteria2://u2"),
            ),
        )

        val linkSource = ConfigSource.XrayLink(
            id = "link1",
            addedAt = 3,
            config = RemoteConfig(id = "l-c1", name = "ded", url = "hysteria2://l1"),
        )
    }
}
