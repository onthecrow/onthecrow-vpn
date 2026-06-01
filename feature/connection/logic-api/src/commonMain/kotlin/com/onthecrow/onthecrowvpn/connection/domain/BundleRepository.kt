package com.onthecrow.onthecrowvpn.connection.domain

import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import kotlinx.coroutines.flow.Flow

interface BundleRepository {
    fun observeBundleId(): Flow<String?>
    suspend fun setBundleId(id: String?)

    fun observeCachedBundle(): Flow<ConfigBundle?>
    suspend fun saveCachedBundle(bundle: ConfigBundle?)

    fun observeSelectedConfigId(): Flow<String?>
    suspend fun selectConfig(id: String?)
}
