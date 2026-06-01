package com.onthecrow.onthecrowvpn.connection.domain

fun interface PrepareConnectionConfigUseCase {
    suspend operator fun invoke(rawUrl: String): ConfigValidationResult
}
