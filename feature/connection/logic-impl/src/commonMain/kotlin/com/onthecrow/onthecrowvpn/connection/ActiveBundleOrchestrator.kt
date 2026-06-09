package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.BundleRepository
import com.onthecrow.onthecrowvpn.connection.model.ActiveBundleState
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.coroutines.ApplicationScopeProvider
import com.onthecrow.onthecrowvpn.firebase.BundleResult
import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * Owns the single live subscription to the user's bundle. Combines the saved
 * bundle id, the Firestore listener output for that id, the locally cached
 * bundle, and the selected config id into a stable `ActiveBundleState`.
 *
 * Side-effects:
 *   - persists every successful Firestore snapshot into DataStore;
 *   - when the bundle changes and the selected config id is missing/invalid,
 *     auto-selects the first config so the UI is never in a no-selection limbo.
 */
internal class ActiveBundleOrchestrator(
    private val repository: BundleRepository,
    private val firestoreClient: FirestoreClient,
    scopeProvider: ApplicationScopeProvider,
) {
    private val scope: CoroutineScope = scopeProvider.scope

    // One-shot guard so a remote deletion triggers the local wipe exactly once, even though `combine`
    // re-emits several times before the cleared DataStore values propagate back. Reset on the next
    // successful snapshot (e.g. the same id is re-added later).
    private var revocationHandled = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val firestoreResults: Flow<Pair<String?, BundleResult?>> =
        repository.observeBundleId()
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id.isNullOrBlank()) {
                    flowOf<Pair<String?, BundleResult?>>(null to null)
                } else {
                    firestoreClient.observeBundle(id).let { resultFlow ->
                        kotlinx.coroutines.flow.flow {
                            emit(id to null) // signal loading until first result arrives
                            resultFlow.collect { res ->
                                emit(id to res)
                            }
                        }
                    }
                }
            }
            .onEach { (_, res) ->
                if (res is BundleResult.Success) {
                    repository.saveCachedBundle(res.bundle)
                }
            }

    private val combined: Flow<ActiveBundleState> = combine(
        repository.observeBundleId(),
        repository.observeCachedBundle(),
        repository.observeSelectedConfigId(),
        firestoreResults,
    ) { savedId, cached, selectedId, (firestoreId, firestoreRes) ->
        // A genuine remote deletion of the bundle we were holding (NotFound for the id we had cached) —
        // as opposed to a transient Error/Unavailable/offline, where we keep the cache and the tunnel.
        val isRevocation = firestoreRes is BundleResult.NotFound &&
            cached != null && cached.id == savedId

        if (firestoreRes is BundleResult.Success) {
            revocationHandled = false
        }
        if (isRevocation && !revocationHandled) {
            revocationHandled = true
            // Wipe local persistence so the config disappears and never resurrects on relaunch.
            scope.launch {
                repository.setBundleId(null)
                repository.saveCachedBundle(null)
                repository.selectConfig(null)
            }
        }

        val effectiveBundle: ConfigBundle? = when {
            isRevocation -> null
            firestoreRes is BundleResult.Success -> firestoreRes.bundle
            // NotFound-without-cache / Error / Unavailable / loading: keep whatever we had cached.
            else -> cached?.takeIf { it.id == savedId }
        }

        val effectiveSelection: String? = when {
            effectiveBundle == null -> null
            effectiveBundle.configs.any { it.id == selectedId } -> selectedId
            else -> effectiveBundle.configs.firstOrNull()?.id
        }

        // If we ended up auto-picking a different selection than what's persisted, persist it.
        if (effectiveSelection != selectedId && effectiveBundle != null) {
            scope.launch { repository.selectConfig(effectiveSelection) }
        }

        ActiveBundleState(
            savedBundleId = savedId,
            bundle = effectiveBundle,
            selectedConfigId = effectiveSelection,
            isLoading = !savedId.isNullOrBlank() && firestoreRes == null && firestoreId == savedId && effectiveBundle == null,
            error = when {
                isRevocation -> REVOKED_MESSAGE
                firestoreRes is BundleResult.Error -> firestoreRes.message
                firestoreRes is BundleResult.NotFound -> "Bundle not found"
                firestoreRes is BundleResult.Unavailable -> "Firebase is not configured"
                else -> null
            },
            revoked = isRevocation,
        )
    }.distinctUntilChanged()

    val state: Flow<ActiveBundleState> = combined.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private companion object {
        const val REVOKED_MESSAGE = "This configuration is no longer available"
    }
}
