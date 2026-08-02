package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.analytics.SourceKind
import com.onthecrow.deltavpn.connection.data.subscription.SubscriptionUrlFetcher
import com.onthecrow.deltavpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.deltavpn.connection.domain.RefreshResult
import com.onthecrow.deltavpn.connection.domain.RefreshSourceUseCase
import com.onthecrow.deltavpn.connection.model.ConfigSource
import com.onthecrow.deltavpn.connection.model.RemoteConfig
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Re-loads one source. Firestore sources just force a listener re-subscribe (live state shows the
 * progress). URL sources re-fetch; on failure the cached configs stay untouched.
 */
@OptIn(ExperimentalTime::class)
internal class RefreshSourceUseCaseImpl(
    private val repository: ConfigSourcesRepository,
    private val orchestrator: ConfigSourcesOrchestrator,
    private val fetcher: SubscriptionUrlFetcher,
    private val analyticsManager: AnalyticsManager,
) : RefreshSourceUseCase {
    override suspend fun invoke(sourceId: String): RefreshResult {
        val source = repository.observeSources().first().firstOrNull { it.id == sourceId }
            ?: return RefreshResult.Ok // deleted meanwhile — nothing to refresh
        return when (source) {
            is ConfigSource.FirestoreSubscription -> {
                orchestrator.refreshFirestoreSource(sourceId)
                analyticsManager.sourceRefreshed(SourceKind.SUBSCRIPTION_ID, failureReason = null)
                RefreshResult.Ok
            }
            is ConfigSource.SubscriptionUrl -> when (val fetched = fetcher.fetch(source.url)) {
                is SubscriptionUrlFetcher.FetchResult.Failure -> {
                    analyticsManager.sourceRefreshed(SourceKind.SUBSCRIPTION_URL, fetched.reason.toRefreshReason())
                    RefreshResult.Failed(fetched.message)
                }
                is SubscriptionUrlFetcher.FetchResult.Success -> {
                    repository.updateSource(sourceId) { src ->
                        if (src is ConfigSource.SubscriptionUrl) {
                            src.copy(
                                title = fetched.title ?: src.title,
                                configs = withStableIds(old = src.configs, new = fetched.configs),
                                lastFetchedAt = Clock.System.now().toEpochMilliseconds(),
                            )
                        } else {
                            src
                        }
                    }
                    analyticsManager.sourceRefreshed(SourceKind.SUBSCRIPTION_URL, failureReason = null)
                    RefreshResult.Ok
                }
            }
            is ConfigSource.XrayLink -> RefreshResult.Ok // nothing to refresh (no event)
        }
    }

    /**
     * Re-fetch assigns fresh random ids; reuse the old id for any config whose url is unchanged so
     * the selection stays put and the sync worker doesn't restart a connected tunnel spuriously.
     */
    private fun withStableIds(old: List<RemoteConfig>, new: List<RemoteConfig>): List<RemoteConfig> {
        val oldByUrl = old.associateBy { it.url }
        return new.map { cfg -> oldByUrl[cfg.url]?.let { cfg.copy(id = it.id) } ?: cfg }
    }
}
