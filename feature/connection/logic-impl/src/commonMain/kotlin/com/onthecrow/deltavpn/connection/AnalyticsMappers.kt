package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.analytics.AddFailureReason
import com.onthecrow.deltavpn.analytics.RefreshFailureReason
import com.onthecrow.deltavpn.analytics.SourceKind
import com.onthecrow.deltavpn.connection.data.subscription.SubscriptionUrlFetcher.FetchFailure
import com.onthecrow.deltavpn.connection.model.ConfigSource

/** Analytics source-kind of a stored source (a category, never the id/url/title/bundle it carries). */
internal fun ConfigSource.analyticsKind(): SourceKind = when (this) {
    is ConfigSource.FirestoreSubscription -> SourceKind.SUBSCRIPTION_ID
    is ConfigSource.SubscriptionUrl -> SourceKind.SUBSCRIPTION_URL
    is ConfigSource.XrayLink -> SourceKind.XRAY_LINK
}

/** Subscription-URL fetch failure -> the add-source reason taxonomy. */
internal fun FetchFailure.toAddReason(): AddFailureReason = when (this) {
    FetchFailure.NETWORK -> AddFailureReason.NETWORK_ERROR
    FetchFailure.HTTP -> AddFailureReason.HTTP_ERROR
    FetchFailure.BODY_READ -> AddFailureReason.NETWORK_ERROR
    FetchFailure.TOO_LARGE -> AddFailureReason.RESPONSE_TOO_LARGE
    FetchFailure.NO_CONFIGS -> AddFailureReason.NO_CONFIGS_FOUND
    FetchFailure.NO_VALID_CONFIGS -> AddFailureReason.NO_VALID_CONFIGS
}

/** Subscription-URL fetch failure -> the refresh reason taxonomy. */
internal fun FetchFailure.toRefreshReason(): RefreshFailureReason = when (this) {
    FetchFailure.NETWORK -> RefreshFailureReason.NETWORK_ERROR
    FetchFailure.HTTP -> RefreshFailureReason.HTTP_ERROR
    FetchFailure.BODY_READ -> RefreshFailureReason.BODY_READ_FAILED
    FetchFailure.TOO_LARGE -> RefreshFailureReason.RESPONSE_TOO_LARGE
    FetchFailure.NO_CONFIGS -> RefreshFailureReason.NO_CONFIGS
    FetchFailure.NO_VALID_CONFIGS -> RefreshFailureReason.NO_VALID_CONFIGS
}
