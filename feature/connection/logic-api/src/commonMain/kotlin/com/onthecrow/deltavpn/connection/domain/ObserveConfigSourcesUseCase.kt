package com.onthecrow.deltavpn.connection.domain

import com.onthecrow.deltavpn.connection.model.ConfigSourcesState
import kotlinx.coroutines.flow.Flow

fun interface ObserveConfigSourcesUseCase {
    operator fun invoke(): Flow<ConfigSourcesState>
}
