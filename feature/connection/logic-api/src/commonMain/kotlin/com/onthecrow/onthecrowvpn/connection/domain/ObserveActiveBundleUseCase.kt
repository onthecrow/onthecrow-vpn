package com.onthecrow.onthecrowvpn.connection.domain

import com.onthecrow.onthecrowvpn.connection.model.ActiveBundleState
import kotlinx.coroutines.flow.Flow

fun interface ObserveActiveBundleUseCase {
    operator fun invoke(): Flow<ActiveBundleState>
}
