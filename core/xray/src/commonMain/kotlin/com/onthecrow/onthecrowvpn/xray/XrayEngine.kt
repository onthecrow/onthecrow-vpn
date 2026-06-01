package com.onthecrow.onthecrowvpn.xray

import com.onthecrow.onthecrowvpn.connection.model.ConnectionConfigSummary

interface XrayEngine {
    suspend fun validate(rawConfig: String): XrayValidationResult
    suspend fun setTunFd(fd: Int)
    suspend fun start(xrayJson: String): XrayRunResult
    suspend fun stop(): XrayRunResult
}

sealed interface XrayValidationResult {
    data class Valid(
        val xrayJson: String,
        val summary: ConnectionConfigSummary,
    ) : XrayValidationResult

    data class Invalid(val message: String) : XrayValidationResult
}

sealed interface XrayRunResult {
    data object Success : XrayRunResult
    data class Failure(val message: String) : XrayRunResult
}
