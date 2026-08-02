package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.connection.domain.CollapsedGroupsUseCase
import com.onthecrow.deltavpn.connection.domain.ConfigSourcesRepository
import kotlinx.coroutines.flow.Flow

internal class CollapsedGroupsUseCaseImpl(
    private val repository: ConfigSourcesRepository,
) : CollapsedGroupsUseCase {
    override fun observe(): Flow<Set<String>> = repository.observeCollapsedGroups()

    override suspend fun setCollapsed(groupKey: String, collapsed: Boolean) =
        repository.setGroupCollapsed(groupKey, collapsed)
}
