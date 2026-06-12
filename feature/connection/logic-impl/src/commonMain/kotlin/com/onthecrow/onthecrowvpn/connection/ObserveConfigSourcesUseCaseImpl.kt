package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ObserveConfigSourcesUseCase
import com.onthecrow.onthecrowvpn.connection.model.ConfigSourcesState
import kotlinx.coroutines.flow.Flow

internal class ObserveConfigSourcesUseCaseImpl(
    private val orchestrator: ConfigSourcesOrchestrator,
) : ObserveConfigSourcesUseCase {
    override fun invoke(): Flow<ConfigSourcesState> = orchestrator.state
}
