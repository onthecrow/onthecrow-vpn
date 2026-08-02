package com.onthecrow.deltavpn.connection.domain

import kotlinx.coroutines.flow.Flow

/** Persisted collapsed/expanded state of source groups on the connection screen. */
interface CollapsedGroupsUseCase {
    fun observe(): Flow<Set<String>>
    suspend fun setCollapsed(groupKey: String, collapsed: Boolean)
}
