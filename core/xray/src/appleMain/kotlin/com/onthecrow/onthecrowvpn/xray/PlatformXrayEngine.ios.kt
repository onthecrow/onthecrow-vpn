package com.onthecrow.onthecrowvpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import libxray.LibXrayConvertShareLinksToXrayJson

@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual class PlatformXrayEngine : XrayEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val summarizer = XrayConfigSummarizer(json)
    private val sanitizer = XrayConfigSanitizer(json)

    override suspend fun validate(rawConfig: String): XrayValidationResult {
        val trimmed = rawConfig.trim()
        if (trimmed.isBlank()) return XrayValidationResult.Invalid("Configuration is empty")

        val rawXrayJson = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            // Convert the share link via the same libXray entry point used on Android,
            // so the resulting Xray JSON matches byte-for-byte. Request and response are
            // both base64-wrapped (libXray's CallResponse envelope).
            val b64in = Base64.Default.encode(trimmed.encodeToByteArray())
            val response = LibXrayConvertShareLinksToXrayJson(b64in)
            val decoded = runCatching { Base64.Default.decode(response).decodeToString() }.getOrNull()
                ?: return XrayValidationResult.Invalid("Malformed libXray response")
            val root = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
                ?: return XrayValidationResult.Invalid("Malformed libXray response")
            val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!success) {
                return XrayValidationResult.Invalid(
                    root["error"]?.jsonPrimitive?.contentOrNull ?: "libXray rejected the link",
                )
            }
            val data = root["data"] ?: return XrayValidationResult.Invalid("libXray returned empty config")
            json.encodeToString(JsonElement.serializer(), data)
        }

        val xrayJson = sanitizer.sanitize(rawXrayJson)
        return XrayValidationResult.Valid(
            xrayJson = xrayJson,
            summary = summarizer.summarize(xrayJson, fallbackTitle = "Xray config"),
        )
    }

    // The tunnel is run by the Network Extension (PacketTunnelProvider → OnthecrowTunnelCore),
    // not by this engine — these stay no-ops on iOS.
    override suspend fun setTunFd(fd: Int) = Unit

    override suspend fun start(xrayJson: String): XrayRunResult = XrayRunResult.Success

    override suspend fun stop(): XrayRunResult = XrayRunResult.Success
}
