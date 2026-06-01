package com.onthecrow.onthecrowvpn.connection.domain

import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig

sealed interface ConfigValidationResult {
    data class Valid(val config: ValidatedConnectionConfig) : ConfigValidationResult
    data class Invalid(val message: String) : ConfigValidationResult
}
