package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.data.subscription.SubscriptionUrlFetcher
import com.onthecrow.onthecrowvpn.connection.domain.AddSourceResult
import com.onthecrow.onthecrowvpn.connection.domain.AddSourceUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.ConfigSource
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.firebase.BundleResult
import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import com.onthecrow.onthecrowvpn.xray.XrayConfigSummarizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Validation-first adding: a source is persisted only after its content is proven usable; any failure
 * returns [AddSourceResult.Invalid] with a user-facing message and persists nothing. Wrong-kind
 * pastes (a URL into "Xray URL" etc.) get a redirecting hint instead of a generic parse error.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
internal class AddSourceUseCaseImpl(
    private val repository: ConfigSourcesRepository,
    private val firestoreClient: FirestoreClient,
    private val fetcher: SubscriptionUrlFetcher,
    private val prepareConnectionConfig: PrepareConnectionConfigUseCase,
    private val summarizer: XrayConfigSummarizer,
) : AddSourceUseCase {

    override suspend fun addSubscriptionId(raw: String): AddSourceResult {
        val id = raw.trim()
        if (id.isEmpty()) return AddSourceResult.Invalid("Clipboard does not contain a subscription ID")
        if (id.contains("://")) {
            return AddSourceResult.Invalid("This looks like a URL — use “Subscription URL” instead")
        }
        if (id.any { it.isWhitespace() } || id.contains('/')) {
            return AddSourceResult.Invalid("Subscription ID contains invalid characters")
        }
        val duplicate = repository.observeSources().first()
            .filterIsInstance<ConfigSource.FirestoreSubscription>()
            .any { it.bundleId == id }
        if (duplicate) return AddSourceResult.Invalid("This subscription is already added")

        // Activation check: the document must actually resolve before we keep the id.
        val result = withTimeoutOrNull(ACTIVATION_TIMEOUT_MS) { firestoreClient.observeBundle(id).first() }
        return when (result) {
            is BundleResult.Success -> {
                repository.addSource(
                    ConfigSource.FirestoreSubscription(
                        id = Uuid.random().toString(),
                        addedAt = now(),
                        bundleId = id,
                        cachedBundle = result.bundle,
                    ),
                )
                AddSourceResult.Added
            }
            is BundleResult.NotFound -> AddSourceResult.Invalid("Subscription “$id” was not found")
            is BundleResult.Error -> AddSourceResult.Invalid(result.message)
            BundleResult.Unavailable -> AddSourceResult.Invalid("Firebase is not configured")
            null -> AddSourceResult.Invalid("Timed out checking the subscription — try again")
        }
    }

    override suspend fun addSubscriptionUrl(raw: String): AddSourceResult {
        val url = raw.trim()
        if (url.isEmpty()) return AddSourceResult.Invalid("Clipboard does not contain a subscription URL")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return if (url.contains("://")) {
                AddSourceResult.Invalid("This looks like a config link — use “Xray URL” instead")
            } else {
                AddSourceResult.Invalid("Subscription URL must start with http:// or https://")
            }
        }
        val normalized = normalizeSubscriptionUrl(url)
        val duplicate = repository.observeSources().first()
            .filterIsInstance<ConfigSource.SubscriptionUrl>()
            .any { normalizeSubscriptionUrl(it.url) == normalized }
        if (duplicate) return AddSourceResult.Invalid("This subscription URL is already added")

        return when (val fetched = fetcher.fetch(url)) {
            is SubscriptionUrlFetcher.FetchResult.Failure -> AddSourceResult.Invalid(fetched.message)
            is SubscriptionUrlFetcher.FetchResult.Success -> {
                repository.addSource(
                    ConfigSource.SubscriptionUrl(
                        id = Uuid.random().toString(),
                        addedAt = now(),
                        url = url,
                        title = fetched.title,
                        configs = fetched.configs,
                        lastFetchedAt = now(),
                    ),
                )
                AddSourceResult.Added
            }
        }
    }

    override suspend fun addXrayLink(raw: String): AddSourceResult {
        val link = raw.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
            ?: return AddSourceResult.Invalid("Clipboard does not contain a config link")
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return AddSourceResult.Invalid("This looks like a subscription URL — use “Subscription URL” instead")
        }
        if (!link.contains("://")) {
            return AddSourceResult.Invalid("Not a config link (expected e.g. vless:// or hysteria2://)")
        }
        val duplicate = repository.observeSources().first()
            .filterIsInstance<ConfigSource.XrayLink>()
            .any { it.config.url == link }
        if (duplicate) return AddSourceResult.Invalid("This config link is already added")

        // Full engine validation (share-link conversion + xray test run) before persisting.
        when (val validation = prepareConnectionConfig(link)) {
            is ConfigValidationResult.Invalid -> return AddSourceResult.Invalid(validation.message)
            is ConfigValidationResult.Valid -> Unit
        }
        val summary = summarizer.summarizeShareText(link)
        repository.addSource(
            ConfigSource.XrayLink(
                id = Uuid.random().toString(),
                addedAt = now(),
                config = RemoteConfig(
                    id = Uuid.random().toString(),
                    name = summary.title,
                    location = null,
                    url = link,
                    type = summary.protocol,
                ),
            ),
        )
        return AddSourceResult.Added
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val ACTIVATION_TIMEOUT_MS = 10_000L
    }
}
