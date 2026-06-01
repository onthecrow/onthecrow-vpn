package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.BundleRepository
import com.onthecrow.onthecrowvpn.connection.domain.LoadBundleUseCase

internal class LoadBundleUseCaseImpl(
    private val repository: BundleRepository,
) : LoadBundleUseCase {
    override suspend fun invoke(id: String) {
        val trimmed = id.trim()
        if (trimmed.isBlank()) return
        repository.selectConfig(null)
        repository.saveCachedBundle(null)
        // Force the Firestore subscription to restart even if the id is unchanged
        // (e.g., retry after PERMISSION_DENIED). Toggling to null first invalidates
        // distinctUntilChanged in the orchestrator's flatMapLatest.
        repository.setBundleId(null)
        repository.setBundleId(trimmed)
    }
}
