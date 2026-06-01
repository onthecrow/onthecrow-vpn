package com.onthecrow.onthecrowvpn.connection.model

data class ValidatedConnectionConfig(
    val rawConfig: String,
    val xrayJson: String,
    val summary: ConnectionConfigSummary,
)
