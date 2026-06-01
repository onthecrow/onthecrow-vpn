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

internal class PrepareConnectionConfigUseCaseImplTest {
    @Test
    fun acceptedPayloadReturnsXrayJson() = runTest {
        val xrayEngine = FakeXrayEngine(
            XrayValidationResult.Valid(
                xrayJson = SAMPLE_XRAY_JSON,
                summary = sampleSummary(),
            )
        )
        val useCase = PrepareConnectionConfigUseCaseImpl(xrayEngine)

        val result = useCase("  vless://sample  ")

        val valid = assertIs<ConfigValidationResult.Valid>(result)
        assertEquals(SAMPLE_XRAY_JSON, valid.xrayJson)
        assertEquals(listOf("vless://sample"), xrayEngine.validatedInputs)
    }

    @Test
    fun invalidPayloadSurfacesMessage() = runTest {
        val xrayEngine = FakeXrayEngine(XrayValidationResult.Invalid("Xray rejected configuration"))
        val useCase = PrepareConnectionConfigUseCaseImpl(xrayEngine)

        val result = useCase("broken")

        val invalid = assertIs<ConfigValidationResult.Invalid>(result)
        assertEquals("Xray rejected configuration", invalid.message)
        assertEquals(listOf("broken"), xrayEngine.validatedInputs)
    }

    @Test
    fun blankPayloadIsRejectedBeforeXray() = runTest {
        val xrayEngine = FakeXrayEngine(XrayValidationResult.Invalid("unused"))
        val useCase = PrepareConnectionConfigUseCaseImpl(xrayEngine)

        val result = useCase("   ")

        val invalid = assertIs<ConfigValidationResult.Invalid>(result)
        assertEquals("Configuration is empty", invalid.message)
        assertEquals(emptyList(), xrayEngine.validatedInputs)
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
