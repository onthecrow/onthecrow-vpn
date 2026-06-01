package com.onthecrow.onthecrowvpn.connection.domain

import kotlinx.coroutines.flow.Flow

interface ConnectionConfigRepository {
    fun observeRawConfig(): Flow<String?>
    suspend fun saveRawConfig(rawConfig: String)
}
