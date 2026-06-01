package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConnectionConfigRepository
import com.onthecrow.onthecrowvpn.connection.domain.ObserveSavedConnectionConfigUseCase

internal class ObserveSavedConnectionConfigUseCaseImpl(
    private val repository: ConnectionConfigRepository,
) : ObserveSavedConnectionConfigUseCase {
    override fun invoke() = repository.observeRawConfig()
}
