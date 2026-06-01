package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConnectionConfigRepository
import com.onthecrow.onthecrowvpn.connection.domain.SaveConnectionConfigUseCase

internal class SaveConnectionConfigUseCaseImpl(
    private val repository: ConnectionConfigRepository,
) : SaveConnectionConfigUseCase {
    override suspend fun invoke(rawConfig: String) {
        repository.saveRawConfig(rawConfig)
    }
}
