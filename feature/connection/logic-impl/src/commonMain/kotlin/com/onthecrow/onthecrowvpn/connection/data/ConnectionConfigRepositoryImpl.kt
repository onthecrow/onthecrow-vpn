package com.onthecrow.onthecrowvpn.connection.data

import com.onthecrow.onthecrowvpn.connection.data.datastore.ConnectionConfigPreferencesDataSource
import com.onthecrow.onthecrowvpn.connection.domain.ConnectionConfigRepository
import kotlinx.coroutines.flow.Flow

internal class ConnectionConfigRepositoryImpl(
    private val dataSource: ConnectionConfigPreferencesDataSource,
) : ConnectionConfigRepository {
    override fun observeRawConfig(): Flow<String?> = dataSource.observeRawConfig()

    override suspend fun saveRawConfig(rawConfig: String) {
        dataSource.saveRawConfig(rawConfig)
    }
}
