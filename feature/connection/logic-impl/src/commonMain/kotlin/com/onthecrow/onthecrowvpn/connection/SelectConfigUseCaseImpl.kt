package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.onthecrowvpn.connection.domain.SelectConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.ConfigRef

internal class SelectConfigUseCaseImpl(
    private val repository: ConfigSourcesRepository,
) : SelectConfigUseCase {
    override suspend fun invoke(ref: ConfigRef?) {
        repository.setSelection(ref)
    }
}
