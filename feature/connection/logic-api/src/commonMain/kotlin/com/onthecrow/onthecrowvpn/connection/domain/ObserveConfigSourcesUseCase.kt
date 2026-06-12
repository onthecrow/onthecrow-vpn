package com.onthecrow.onthecrowvpn.connection.domain

import com.onthecrow.onthecrowvpn.connection.model.ConfigSourcesState
import kotlinx.coroutines.flow.Flow

fun interface ObserveConfigSourcesUseCase {
    operator fun invoke(): Flow<ConfigSourcesState>
}
