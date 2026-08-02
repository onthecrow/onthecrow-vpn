package com.onthecrow.deltavpn.connection.domain

/**
 * Removes a source (a whole subscription group, or a single xray link by its per-link source id).
 * If the current selection lives in this source and the VPN is up, disconnects first.
 */
fun interface RemoveSourceUseCase {
    suspend operator fun invoke(sourceId: String)
}
