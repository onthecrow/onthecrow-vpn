package com.onthecrow.onthecrowvpn.connection.domain

import kotlinx.coroutines.flow.Flow

fun interface ObserveSavedConnectionConfigUseCase {
    operator fun invoke(): Flow<String?>
}
