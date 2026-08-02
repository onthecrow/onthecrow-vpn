package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.connection.domain.ObserveConfigSourcesUseCase
import com.onthecrow.deltavpn.connection.model.ConfigSourcesState
import kotlinx.coroutines.flow.Flow

internal class ObserveConfigSourcesUseCaseImpl(
    private val orchestrator: ConfigSourcesOrchestrator,
) : ObserveConfigSourcesUseCase {
    override fun invoke(): Flow<ConfigSourcesState> = orchestrator.state
}
