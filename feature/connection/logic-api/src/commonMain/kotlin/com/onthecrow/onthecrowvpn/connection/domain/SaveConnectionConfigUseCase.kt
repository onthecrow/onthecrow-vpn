package com.onthecrow.onthecrowvpn.connection.domain

fun interface SaveConnectionConfigUseCase {
    suspend operator fun invoke(rawConfig: String)
}
