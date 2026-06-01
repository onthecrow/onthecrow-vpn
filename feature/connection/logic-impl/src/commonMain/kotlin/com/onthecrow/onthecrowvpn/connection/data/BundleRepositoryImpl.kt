package com.onthecrow.onthecrowvpn.connection.data

import com.onthecrow.onthecrowvpn.connection.data.datastore.ConnectionConfigPreferencesDataSource
import com.onthecrow.onthecrowvpn.connection.domain.BundleRepository
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import kotlinx.coroutines.flow.Flow

internal class BundleRepositoryImpl(
    private val dataSource: ConnectionConfigPreferencesDataSource,
) : BundleRepository {
    override fun observeBundleId(): Flow<String?> = dataSource.observeBundleId()
    override suspend fun setBundleId(id: String?) = dataSource.saveBundleId(id)

    override fun observeCachedBundle(): Flow<ConfigBundle?> = dataSource.observeCachedBundle()
    override suspend fun saveCachedBundle(bundle: ConfigBundle?) = dataSource.saveCachedBundle(bundle)

    override fun observeSelectedConfigId(): Flow<String?> = dataSource.observeSelectedConfigId()
    override suspend fun selectConfig(id: String?) = dataSource.saveSelectedConfigId(id)
}
