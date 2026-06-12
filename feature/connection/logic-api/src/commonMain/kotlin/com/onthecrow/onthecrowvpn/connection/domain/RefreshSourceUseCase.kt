package com.onthecrow.onthecrowvpn.connection.domain

sealed interface RefreshResult {
    data object Ok : RefreshResult

    /** Refresh failed; cached configs were left untouched. [message] is user-facing. */
    data class Failed(val message: String) : RefreshResult
}

/** Re-loads a subscription source: Firestore re-subscribe or subscription-URL re-fetch. */
fun interface RefreshSourceUseCase {
    suspend operator fun invoke(sourceId: String): RefreshResult
}
