package com.onthecrow.onthecrowvpn.connection.data.subscription

import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.xray.XrayConfigSummarizer
import com.onthecrow.onthecrowvpn.xray.XrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayValidationResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Downloads a subscription URL (any host/IP, any port, any path) and turns it into configs.
 *
 * Validation goes through libXray itself: [XrayEngine.validate] feeds the raw text to
 * `convertShareLinksToXrayJson`, which natively understands base64 subscription payloads and
 * multi-line link lists — so the whole body is validated as-is first. If that fails (e.g. one broken
 * link among good ones poisons the batch), we fall back to validating line by line and keep only the
 * links that pass. The per-config list itself is built by splitting locally ([SubscriptionPayloadParser])
 * because the UI needs each link as an individually selectable config.
 */
internal class SubscriptionUrlFetcher(
    private val httpClient: HttpClient,
    private val xrayEngine: XrayEngine,
    private val summarizer: XrayConfigSummarizer,
) {
    sealed interface FetchResult {
        data class Success(val title: String?, val configs: List<RemoteConfig>) : FetchResult
        data class Failure(val message: String) : FetchResult
    }

    suspend fun fetch(url: String): FetchResult {
        val response = runCatching { httpClient.get(url) }
            .getOrElse { return FetchResult.Failure(it.message ?: "Network error") }
        if (!response.status.isSuccess()) {
            return FetchResult.Failure("Subscription URL returned HTTP ${response.status.value}")
        }
        val body = runCatching { response.bodyAsText() }
            .getOrElse { return FetchResult.Failure("Failed to read subscription response") }
        if (body.length > MAX_BODY_CHARS) {
            return FetchResult.Failure("Subscription response is too large")
        }

        val links = SubscriptionPayloadParser.parseLinks(body)
        if (links.isEmpty()) {
            return FetchResult.Failure("No configs found at this URL")
        }

        val validLinks = when (xrayEngine.validate(body.trim())) {
            is XrayValidationResult.Valid -> links
            // One bad link can poison the whole batch — salvage the individually valid ones.
            is XrayValidationResult.Invalid ->
                links.filter { xrayEngine.validate(it) is XrayValidationResult.Valid }
        }
        if (validLinks.isEmpty()) {
            return FetchResult.Failure("No valid configs at this URL")
        }

        val title = SubscriptionPayloadParser.decodeProfileTitle(response.headers["profile-title"])
        return FetchResult.Success(title, validLinks.map(::toRemoteConfig))
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun toRemoteConfig(link: String): RemoteConfig {
        val summary = summarizer.summarizeShareText(link)
        return RemoteConfig(
            id = Uuid.random().toString(),
            name = summary.title,
            location = null,
            url = link,
            type = summary.protocol,
        )
    }

    private companion object {
        // Real subscriptions are a few KB; anything huge is an HTML page or abuse.
        const val MAX_BODY_CHARS = 2_000_000
    }
}
