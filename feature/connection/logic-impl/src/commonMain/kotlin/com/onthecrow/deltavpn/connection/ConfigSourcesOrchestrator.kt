package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.deltavpn.connection.model.ConfigRef
import com.onthecrow.deltavpn.connection.model.ConfigRow
import com.onthecrow.deltavpn.connection.model.ConfigSource
import com.onthecrow.deltavpn.connection.model.ConfigSourcesState
import com.onthecrow.deltavpn.connection.model.SourceGroup
import com.onthecrow.deltavpn.connection.model.SourceKind
import com.onthecrow.deltavpn.coroutines.ApplicationScopeProvider
import com.onthecrow.deltavpn.firebase.BundleResult
import com.onthecrow.deltavpn.firebase.FirestoreClient
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the live view over ALL config sources: a dynamic set of Firestore listeners (one per
 * subscription-id source) plus the statically-cached URL/link sources, combined with the persisted
 * global selection into one [ConfigSourcesState].
 *
 * Side-effects (same contracts as the old single-bundle orchestrator):
 *  - persists every successful Firestore snapshot into the source's cachedBundle;
 *  - revocation: NotFound for a source we hold a cache for → remove that source (one-shot per
 *    source), clear the selection if it lived there and signal [ConfigSourcesState.revokedActiveSelection];
 *  - auto-selects the first available config when the selection is missing/invalid.
 */
internal class ConfigSourcesOrchestrator(
    private val repository: ConfigSourcesRepository,
    private val firestoreClient: FirestoreClient,
    scopeProvider: ApplicationScopeProvider,
) {
    private val scope: CoroutineScope = scopeProvider.scope

    // Bumping a source's nonce changes its SubKey → flatMapLatest re-subscribes that listener
    // (replaces the legacy setBundleId(null)→id toggle used for forced refresh).
    private val refreshNonces = MutableStateFlow<Map<String, Int>>(emptyMap())

    // One-shot guard per source: a remote deletion wipes it exactly once even though combine re-emits
    // several times before the updated DataStore values propagate back. Reset on a later Success
    // (same source id can only reappear via re-add, which makes a fresh id — this is belt&braces).
    private val handledRevocations = mutableSetOf<String>()

    fun refreshFirestoreSource(sourceId: String) {
        refreshNonces.update { it + (sourceId to (it[sourceId] ?: 0) + 1) }
    }

    private data class SubKey(val sourceId: String, val bundleId: String, val nonce: Int)

    // Map<sourceId, BundleResult?> for every Firestore source; null = listener attached, no result yet.
    // Tradeoff: flatMapLatest tears down ALL listeners whenever the key set changes (source added/
    // removed/refreshed). Fine for a handful of sources; upgrade path is channelFlow + Map<String, Job>.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val firestoreResults: Flow<Map<String, BundleResult?>> =
        combine(repository.observeSources(), refreshNonces) { sources, nonces ->
            sources.filterIsInstance<ConfigSource.FirestoreSubscription>()
                .map { SubKey(it.id, it.bundleId, nonces[it.id] ?: 0) }
        }
            .distinctUntilChanged()
            .flatMapLatest { subs ->
                if (subs.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        subs.map { sub ->
                            firestoreClient.observeBundle(sub.bundleId)
                                .map<BundleResult, BundleResult?> { it }
                                .onStart { emit(null) }
                                .map { sub.sourceId to it }
                        },
                    ) { pairs -> pairs.toMap() }
                }
            }
            .onEach { results ->
                results.forEach { (sourceId, res) ->
                    if (res is BundleResult.Success) {
                        scope.launch {
                            repository.updateSource(sourceId) { src ->
                                if (src is ConfigSource.FirestoreSubscription && src.cachedBundle != res.bundle) {
                                    src.copy(cachedBundle = res.bundle)
                                } else {
                                    src
                                }
                            }
                        }
                    }
                }
            }

    private val combined: Flow<ConfigSourcesState> = combine(
        repository.observeSources(),
        repository.observeSelection(),
        firestoreResults,
    ) { sources, selection, fsResults ->
        // A later Success for a source clears its one-shot flag (defensive; see field comment).
        sources.forEach { if (fsResults[it.id] is BundleResult.Success) handledRevocations.remove(it.id) }

        // Genuine remote deletion = NotFound for a source we hold a cache for (transient
        // Error/Unavailable/loading keeps the cache and the tunnel). Loading markers are `null`,
        // never NotFound, so listener restarts can't masquerade as revocations.
        val revoked = sources.filterIsInstance<ConfigSource.FirestoreSubscription>()
            .filter { fsResults[it.id] is BundleResult.NotFound && it.cachedBundle != null }
            .filter { handledRevocations.add(it.id) }
        val revokedIds = revoked.map { it.id }.toSet()
        val revokedActive = selection != null && selection.sourceId in revokedIds
        if (revoked.isNotEmpty()) {
            scope.launch {
                // One coroutine so selection-clear and removal can't interleave with another pass.
                if (revokedActive) repository.setSelection(null)
                repository.removeSources(revokedIds)
            }
        }

        val groups = buildGroups(sources.filterNot { it.id in revokedIds }, fsResults)
        val allRows = groups.flatMap(SourceGroup::rows)
        val effective: ConfigRef? = when {
            revokedActive -> null
            allRows.any { it.ref == selection } -> selection
            else -> allRows.firstOrNull()?.ref
        }
        if (effective != selection && !revokedActive) {
            scope.launch { repository.setSelection(effective) }
        }

        ConfigSourcesState(
            groups = groups,
            selected = effective,
            selectedConfig = allRows.firstOrNull { it.ref == effective }?.config,
            revokedActiveSelection = revokedActive,
            revokedSourceTitles = revoked.map { it.cachedBundle?.name ?: it.bundleId },
        )
    }.distinctUntilChanged()

    // The migration must complete before anything reads the new keys (worker starts eagerly).
    val state: Flow<ConfigSourcesState> =
        flow {
            repository.migrateLegacyIfNeeded()
            emitAll(combined)
        }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private fun buildGroups(
        sources: List<ConfigSource>,
        fsResults: Map<String, BundleResult?>,
    ): List<SourceGroup> {
        val firestoreGroups = sources.filterIsInstance<ConfigSource.FirestoreSubscription>()
            .sortedBy { it.addedAt }
            .map { src ->
                val result = fsResults[src.id]
                val bundle = (result as? BundleResult.Success)?.bundle ?: src.cachedBundle
                SourceGroup(
                    sourceId = src.id,
                    kind = SourceKind.SUBSCRIPTION_ID,
                    title = bundle?.name?.takeIf { it.isNotBlank() } ?: src.bundleId,
                    subtitle = src.bundleId,
                    rows = bundle?.configs.orEmpty().map { ConfigRow(ConfigRef(src.id, it.id), it) },
                    isLoading = result == null && bundle == null,
                    error = when {
                        result is BundleResult.Error -> result.message
                        result is BundleResult.NotFound && bundle == null -> "Bundle not found"
                        result is BundleResult.Unavailable -> "Firebase is not configured"
                        else -> null
                    },
                    addedAt = src.addedAt,
                )
            }

        val urlGroups = sources.filterIsInstance<ConfigSource.SubscriptionUrl>()
            .sortedBy { it.addedAt }
            .map { src ->
                SourceGroup(
                    sourceId = src.id,
                    kind = SourceKind.SUBSCRIPTION_URL,
                    title = src.title?.takeIf { it.isNotBlank() } ?: hostOf(src.url),
                    subtitle = src.url,
                    rows = src.configs.map { ConfigRow(ConfigRef(src.id, it.id), it) },
                    addedAt = src.addedAt,
                )
            }

        val links = sources.filterIsInstance<ConfigSource.XrayLink>().sortedBy { it.addedAt }
        val linkGroup = if (links.isEmpty()) {
            null
        } else {
            SourceGroup(
                sourceId = SourceGroup.XRAY_LINKS_GROUP_ID,
                kind = SourceKind.XRAY_LINK,
                title = "Xray links",
                // Rows keep their REAL per-link source ids so swipe-delete/selection target the link.
                rows = links.map { ConfigRow(ConfigRef(it.id, it.config.id), it.config) },
                addedAt = links.first().addedAt,
            )
        }

        return firestoreGroups + urlGroups + listOfNotNull(linkGroup)
    }

    private fun hostOf(url: String): String =
        runCatching { Url(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
}
