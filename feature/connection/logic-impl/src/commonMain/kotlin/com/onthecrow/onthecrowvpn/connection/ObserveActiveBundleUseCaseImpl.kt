package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ObserveActiveBundleUseCase
import com.onthecrow.onthecrowvpn.connection.model.ActiveBundleState
import kotlinx.coroutines.flow.Flow

internal class ObserveActiveBundleUseCaseImpl(
    private val orchestrator: ActiveBundleOrchestrator,
) : ObserveActiveBundleUseCase {
    override fun invoke(): Flow<ActiveBundleState> = orchestrator.state
}
