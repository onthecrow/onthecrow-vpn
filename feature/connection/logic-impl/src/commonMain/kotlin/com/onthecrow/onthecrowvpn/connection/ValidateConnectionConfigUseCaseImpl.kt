package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.SaveConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ValidateConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import com.onthecrow.onthecrowvpn.xray.XrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayValidationResult

internal class ValidateConnectionConfigUseCaseImpl(
    private val xrayEngine: XrayEngine,
    private val saveConnectionConfigUseCase: SaveConnectionConfigUseCase,
) : ValidateConnectionConfigUseCase {
    override suspend fun invoke(rawConfig: String): ConfigValidationResult {
        val trimmed = rawConfig.trim()
        if (trimmed.isBlank()) {
            return ConfigValidationResult.Invalid("Configuration is empty")
        }

        return when (val result = xrayEngine.validate(trimmed)) {
            is XrayValidationResult.Valid -> {
                saveConnectionConfigUseCase(trimmed)
                ConfigValidationResult.Valid(
                    ValidatedConnectionConfig(
                        rawConfig = trimmed,
                        xrayJson = result.xrayJson,
                        summary = result.summary,
                    )
                )
            }

            is XrayValidationResult.Invalid -> ConfigValidationResult.Invalid(result.message)
        }
    }
}
