package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.xray.XrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayValidationResult

internal class PrepareConnectionConfigUseCaseImpl(
    private val xrayEngine: XrayEngine,
) : PrepareConnectionConfigUseCase {
    override suspend fun invoke(rawUrl: String): ConfigValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return ConfigValidationResult.Invalid("Configuration is empty")
        }
        return when (val result = xrayEngine.validate(trimmed)) {
            is XrayValidationResult.Valid -> ConfigValidationResult.Valid(result.xrayJson)
            is XrayValidationResult.Invalid -> ConfigValidationResult.Invalid(result.message)
        }
    }
}
