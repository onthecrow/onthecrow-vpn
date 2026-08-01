package com.onthecrow.onthecrowvpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.io.encoding.ExperimentalEncodingApi
import libxray.LibXrayInvoke

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
            // Convert the share link via the same libXray entry point used on Android, so the
            // resulting Xray JSON matches byte-for-byte. Since v26.7.11 that is the single `invoke`
            // dispatcher taking a JSON envelope — the base64 wrapping is gone in both directions.
            val request = buildJsonObject {
                put("apiVersion", 1)
                put("method", "convertShareLinksToXrayJson")
                put("payload", buildJsonObject { put("text", trimmed) })
            }
            val response = LibXrayInvoke(request.toString())
            val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
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
        // The tun carries the whole device, so an outbound with no encryption would publish every
        // app's traffic while the first-run disclosure promises the opposite. Refused here, at the
        // only gate every config passes, rather than at connect time.
        XrayOutboundEncryption.cleartextReason(xrayJson)?.let { reason ->
            return XrayValidationResult.Invalid(reason)
        }
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
