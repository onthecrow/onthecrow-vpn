package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.deltavpn.connection.domain.SelectConfigUseCase
import com.onthecrow.deltavpn.connection.model.ConfigRef

internal class SelectConfigUseCaseImpl(
    private val repository: ConfigSourcesRepository,
) : SelectConfigUseCase {
    override suspend fun invoke(ref: ConfigRef?) {
        repository.setSelection(ref)
    }
}
