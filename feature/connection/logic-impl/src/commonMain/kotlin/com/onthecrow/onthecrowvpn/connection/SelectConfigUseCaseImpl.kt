package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.BundleRepository
import com.onthecrow.onthecrowvpn.connection.domain.SelectConfigUseCase

internal class SelectConfigUseCaseImpl(
    private val repository: BundleRepository,
) : SelectConfigUseCase {
    override suspend fun invoke(configId: String) {
        repository.selectConfig(configId)
    }
}
