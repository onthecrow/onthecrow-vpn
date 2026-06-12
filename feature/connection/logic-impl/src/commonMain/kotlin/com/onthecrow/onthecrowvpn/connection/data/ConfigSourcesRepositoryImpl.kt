package com.onthecrow.onthecrowvpn.connection.data

import com.onthecrow.onthecrowvpn.connection.data.datastore.ConfigSourcesPreferencesDataSource
import com.onthecrow.onthecrowvpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigSource
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal class ConfigSourcesRepositoryImpl(
    private val dataSource: ConfigSourcesPreferencesDataSource,
) : ConfigSourcesRepository {
    override fun observeSources(): Flow<List<ConfigSource>> = dataSource.observeSources()

    override suspend fun addSource(source: ConfigSource) = dataSource.addSource(source)

    override suspend fun removeSources(sourceIds: Collection<String>) = dataSource.removeSources(sourceIds)

    override suspend fun updateSource(sourceId: String, transform: (ConfigSource) -> ConfigSource) =
        dataSource.updateSource(sourceId, transform)

    override fun observeSelection(): Flow<ConfigRef?> = dataSource.observeSelection()

    override suspend fun setSelection(ref: ConfigRef?) = dataSource.setSelection(ref)

    @OptIn(ExperimentalTime::class)
    override suspend fun migrateLegacyIfNeeded() =
        dataSource.migrateLegacyIfNeeded(now = Clock.System.now().toEpochMilliseconds())
}
