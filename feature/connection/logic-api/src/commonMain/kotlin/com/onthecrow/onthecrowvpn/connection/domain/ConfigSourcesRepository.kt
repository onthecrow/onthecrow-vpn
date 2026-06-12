package com.onthecrow.onthecrowvpn.connection.domain

import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigSource
import kotlinx.coroutines.flow.Flow

/** Persisted store of all user-added config sources plus the global selection. */
interface ConfigSourcesRepository {
    fun observeSources(): Flow<List<ConfigSource>>
    suspend fun addSource(source: ConfigSource)
    suspend fun removeSources(sourceIds: Collection<String>)

    /** Applies [transform] to the source with [sourceId]; no-op if it no longer exists. */
    suspend fun updateSource(sourceId: String, transform: (ConfigSource) -> ConfigSource)

    fun observeSelection(): Flow<ConfigRef?>
    suspend fun setSelection(ref: ConfigRef?)

    /** Persisted set of collapsed group keys (per-source UI preference, survives restarts). */
    fun observeCollapsedGroups(): Flow<Set<String>>
    suspend fun setGroupCollapsed(groupKey: String, collapsed: Boolean)

    /** One-time, idempotent migration of the legacy single-bundle keys into the sources list. */
    suspend fun migrateLegacyIfNeeded()
}
