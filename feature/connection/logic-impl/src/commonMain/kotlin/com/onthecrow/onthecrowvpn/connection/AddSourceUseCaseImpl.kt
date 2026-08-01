package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.analytics.AddFailureReason
import com.onthecrow.onthecrowvpn.analytics.AnalyticsManager
import com.onthecrow.onthecrowvpn.analytics.SourceKind
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
 *
 * Each terminal branch also fires analytics via [added]/[invalid] — the bounded failure reason is
 * derived from the branch, never from the user-facing message (which can echo the raw id/URL/host).
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
internal class AddSourceUseCaseImpl(
    private val repository: ConfigSourcesRepository,
    private val firestoreClient: FirestoreClient,
    private val fetcher: SubscriptionUrlFetcher,
    private val prepareConnectionConfig: PrepareConnectionConfigUseCase,
    private val summarizer: XrayConfigSummarizer,
    private val analyticsManager: AnalyticsManager,
) : AddSourceUseCase {

    override suspend fun addSubscriptionId(raw: String): AddSourceResult {
        val kind = SourceKind.SUBSCRIPTION_ID
        val id = raw.trim()
        if (id.isEmpty()) {
            return invalid(kind, AddFailureReason.EMPTY_CLIPBOARD, "Clipboard does not contain a subscription ID")
        }
        if (id.contains("://")) {
            return invalid(kind, AddFailureReason.WRONG_KIND_HINT, "This looks like a URL — use “Subscription URL” instead")
        }
        if (id.any { it.isWhitespace() } || id.contains('/')) {
            return invalid(kind, AddFailureReason.INVALID_CHARS, "Subscription ID contains invalid characters")
        }
        val duplicate = repository.observeSources().first()
            .filterIsInstance<ConfigSource.FirestoreSubscription>()
            .any { it.bundleId == id }
        if (duplicate) return invalid(kind, AddFailureReason.DUPLICATE, "This subscription is already added")

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
                added(kind)
            }
            is BundleResult.NotFound -> invalid(kind, AddFailureReason.NOT_FOUND, "Subscription “$id” was not found")
            is BundleResult.Error -> invalid(kind, AddFailureReason.NETWORK_ERROR, result.message)
            BundleResult.Unavailable -> invalid(kind, AddFailureReason.FIREBASE_UNAVAILABLE, "Firebase is not configured")
            null -> invalid(kind, AddFailureReason.ACTIVATION_TIMEOUT, "Timed out checking the subscription — try again")
        }
    }

    override suspend fun addSubscriptionUrl(raw: String): AddSourceResult {
        val kind = SourceKind.SUBSCRIPTION_URL
        val url = raw.trim()
        if (url.isEmpty()) {
            return invalid(kind, AddFailureReason.EMPTY_CLIPBOARD, "Clipboard does not contain a subscription URL")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return if (url.contains("://")) {
                invalid(kind, AddFailureReason.WRONG_KIND_HINT, "This looks like a config link — use “Xray URL” instead")
            } else {
                invalid(kind, AddFailureReason.INVALID_CHARS, "Subscription URL must start with http:// or https://")
            }
        }
        val normalized = normalizeSubscriptionUrl(url)
        val duplicate = repository.observeSources().first()
            .filterIsInstance<ConfigSource.SubscriptionUrl>()
            .any { normalizeSubscriptionUrl(it.url) == normalized }
        if (duplicate) return invalid(kind, AddFailureReason.DUPLICATE, "This subscription URL is already added")

        return when (val fetched = fetcher.fetch(url)) {
            is SubscriptionUrlFetcher.FetchResult.Failure ->
                invalid(kind, fetched.reason.toAddReason(), fetched.message)
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
                added(kind)
            }
        }
    }

    override suspend fun addXrayLink(raw: String): AddSourceResult {
        val kind = SourceKind.XRAY_LINK
        val link = raw.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
            ?: return invalid(kind, AddFailureReason.EMPTY_CLIPBOARD, "Clipboard does not contain a config link")
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return invalid(kind, AddFailureReason.WRONG_KIND_HINT, "This looks like a subscription URL — use “Subscription URL” instead")
        }
        if (!link.contains("://")) {
            return invalid(kind, AddFailureReason.INVALID_CHARS, "Not a config link (expected e.g. vless:// or hysteria2://)")
        }
        val duplicate = repository.observeSources().first()
            .filterIsInstance<ConfigSource.XrayLink>()
            .any { it.config.url == link }
        if (duplicate) return invalid(kind, AddFailureReason.DUPLICATE, "This config link is already added")

        // Full engine validation (share-link conversion + xray test run) before persisting.
        when (val validation = prepareConnectionConfig(link)) {
            is ConfigValidationResult.Invalid -> return invalid(kind, AddFailureReason.ENGINE_INVALID, validation.message)
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
        return added(kind)
    }

    private suspend fun added(kind: SourceKind): AddSourceResult {
        analyticsManager.sourceAdded(kind, repository.observeSources().first().size)
        return AddSourceResult.Added
    }

    private fun invalid(kind: SourceKind, reason: AddFailureReason, message: String): AddSourceResult {
        analyticsManager.sourceAddFailed(kind, reason)
        return AddSourceResult.Invalid(message)
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val ACTIVATION_TIMEOUT_MS = 10_000L
    }
}
