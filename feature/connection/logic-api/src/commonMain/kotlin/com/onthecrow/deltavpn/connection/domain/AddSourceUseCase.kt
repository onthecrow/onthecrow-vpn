package com.onthecrow.deltavpn.connection.domain

sealed interface AddSourceResult {
    data object Added : AddSourceResult

    /** Validation/activation failed; nothing was persisted. [message] is user-facing. */
    data class Invalid(val message: String) : AddSourceResult
}

/**
 * Adds a config source from raw clipboard text. Validation-first: a source is persisted only after
 * its content has been verified (Firestore document resolves / subscription downloads and converts /
 * the share link passes the xray engine).
 */
interface AddSourceUseCase {
    suspend fun addSubscriptionId(raw: String): AddSourceResult
    suspend fun addSubscriptionUrl(raw: String): AddSourceResult
    suspend fun addXrayLink(raw: String): AddSourceResult
}
