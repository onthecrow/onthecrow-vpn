package com.onthecrow.onthecrowvpn.connection.domain

fun interface ValidateConnectionConfigUseCase {
    suspend operator fun invoke(rawConfig: String): ConfigValidationResult
}
