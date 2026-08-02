package com.onthecrow.deltavpn.connection.domain

fun interface PrepareConnectionConfigUseCase {
    suspend operator fun invoke(rawUrl: String): ConfigValidationResult
}
