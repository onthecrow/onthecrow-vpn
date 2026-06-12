package com.onthecrow.onthecrowvpn.connection.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user-added origin of VPN configs. The app holds any number of sources of each kind; together they
 * produce the grouped config list on the main screen.
 */
@Serializable
sealed interface ConfigSource {
    /** Stable unique key of the source (UUID assigned at add time). Used for grouping/selection. */
    val id: String

    /** Epoch millis when the user added the source; defines ordering within its section. */
    val addedAt: Long

    /** A Firestore bundle document (`configs/{bundleId}`), live-observed while the app runs. */
    @Serializable
    @SerialName("firestore")
    data class FirestoreSubscription(
        override val id: String,
        override val addedAt: Long,
        val bundleId: String,
        /** Last successful snapshot; keeps the group usable offline and across listener restarts. */
        val cachedBundle: ConfigBundle? = null,
    ) : ConfigSource

    /** An x-ui/v2rayN-style subscription URL fetched over HTTP (on add + manual refresh only). */
    @Serializable
    @SerialName("subscription_url")
    data class SubscriptionUrl(
        override val id: String,
        override val addedAt: Long,
        val url: String,
        /** From the `profile-title` response header; null falls back to the URL host in UI. */
        val title: String? = null,
        val configs: List<RemoteConfig> = emptyList(),
        val lastFetchedAt: Long = 0L,
    ) : ConfigSource

    /** A single share link (vless://, hysteria2://, …). The UI merges all of these into one group. */
    @Serializable
    @SerialName("xray_link")
    data class XrayLink(
        override val id: String,
        override val addedAt: Long,
        val config: RemoteConfig,
    ) : ConfigSource
}
