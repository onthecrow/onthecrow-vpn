package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.onthecrowvpn.connection.domain.RemoveSourceUseCase
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import com.onthecrow.onthecrowvpn.vpn.VpnController
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Removes a source. If the global selection lives in it and the VPN is up (or coming up), tears the
 * tunnel down FIRST and clears the selection, so the orchestrator's auto-pick of the next config
 * can't race a live tunnel (the sync worker only restarts while Connected).
 */
internal class RemoveSourceUseCaseImpl(
    private val repository: ConfigSourcesRepository,
    private val vpnController: VpnController,
) : RemoveSourceUseCase {
    override suspend fun invoke(sourceId: String) {
        val selection = repository.observeSelection().first()
        if (selection?.sourceId == sourceId) {
            val status = vpnController.status.value
            if (status is ConnectionStatus.Connected || status is ConnectionStatus.Connecting) {
                vpnController.disconnect()
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                    vpnController.status
                        .filter { it is ConnectionStatus.Disconnected || it is ConnectionStatus.Error }
                        .first()
                }
            }
            repository.setSelection(null)
        }
        repository.removeSources(listOf(sourceId))
    }

    private companion object {
        const val DISCONNECT_TIMEOUT_MS = 10_000L
    }
}
