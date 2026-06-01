package com.onthecrow.onthecrowvpn.connection.domain

sealed interface ConfigValidationResult {
    data class Valid(val xrayJson: String) : ConfigValidationResult
    data class Invalid(val message: String) : ConfigValidationResult
}
