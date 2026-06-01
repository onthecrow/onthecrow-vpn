package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.model.ConnectionConfigSummary
import com.onthecrow.onthecrowvpn.xray.XrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayRunResult
import com.onthecrow.onthecrowvpn.xray.XrayValidationResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ValidateConnectionConfigUseCaseImplTest {
    @Test
    fun acceptedPayloadIsValidatedAndSaved() = runTest {
        val xrayEngine = FakeXrayEngine(
            XrayValidationResult.Valid(
                xrayJson = SAMPLE_XRAY_JSON,
                summary = sampleSummary(),
            )
        )
        val savedConfigs = mutableListOf<String>()
        val useCase = ValidateConnectionConfigUseCaseImpl(
            xrayEngine = xrayEngine,
            saveConnectionConfigUseCase = { savedConfigs += it },
        )

        val result = useCase("  vless://sample  ")

        val valid = assertIs<ConfigValidationResult.Valid>(result)
        assertEquals("vless://sample", valid.config.rawConfig)
        assertEquals(SAMPLE_XRAY_JSON, valid.config.xrayJson)
        assertEquals(listOf("vless://sample"), xrayEngine.validatedInputs)
        assertEquals(listOf("vless://sample"), savedConfigs)
    }

    @Test
    fun invalidPayloadDoesNotOverwriteSavedConfig() = runTest {
        val xrayEngine = FakeXrayEngine(XrayValidationResult.Invalid("Xray rejected configuration"))
        val savedConfigs = mutableListOf<String>()
        val useCase = ValidateConnectionConfigUseCaseImpl(
            xrayEngine = xrayEngine,
            saveConnectionConfigUseCase = { savedConfigs += it },
        )

        val result = useCase("broken")

        val invalid = assertIs<ConfigValidationResult.Invalid>(result)
        assertEquals("Xray rejected configuration", invalid.message)
        assertEquals(listOf("broken"), xrayEngine.validatedInputs)
        assertTrue(savedConfigs.isEmpty())
    }

    @Test
    fun emptyPayloadIsRejectedBeforeXray() = runTest {
        val xrayEngine = FakeXrayEngine(XrayValidationResult.Invalid("unused"))
        val useCase = ValidateConnectionConfigUseCaseImpl(
            xrayEngine = xrayEngine,
            saveConnectionConfigUseCase = {},
        )

        val result = useCase("   ")

        val invalid = assertIs<ConfigValidationResult.Invalid>(result)
        assertEquals("Configuration is empty", invalid.message)
        assertTrue(xrayEngine.validatedInputs.isEmpty())
    }

    private class FakeXrayEngine(
        private val result: XrayValidationResult,
    ) : XrayEngine {
        val validatedInputs = mutableListOf<String>()

        override suspend fun validate(rawConfig: String): XrayValidationResult {
            validatedInputs += rawConfig
            return result
        }

        override suspend fun setTunFd(fd: Int) = Unit
        override suspend fun start(xrayJson: String): XrayRunResult = XrayRunResult.Success
        override suspend fun stop(): XrayRunResult = XrayRunResult.Success
    }

    private fun sampleSummary() = ConnectionConfigSummary(
        title = "sample",
        protocol = "vless",
        address = "78.17.84.51",
        port = 443,
        security = "reality",
        transport = "tcp",
        sni = "www.microsoft.com",
        outboundCount = 1,
        isAdvanced = false,
    )

    private companion object {
        const val SAMPLE_XRAY_JSON = """{"outbounds":[{"protocol":"vless"}]}"""
    }
}
