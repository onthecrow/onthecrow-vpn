package com.onthecrow.onthecrowvpn.xray

import com.onthecrow.onthecrowvpn.connection.model.ConnectionConfigSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class XrayConfigSummarizer(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun summarize(xrayJson: String, fallbackTitle: String = "Xray config"): ConnectionConfigSummary {
        val root = runCatching { json.parseToJsonElement(xrayJson).jsonObject }.getOrNull()
            ?: return advanced(fallbackTitle)
        val outbounds = root["outbounds"]?.jsonArrayOrNull().orEmpty()
        val firstOutbound = outbounds.firstOrNull()?.jsonObjectOrNull()
        val outboundCount = outbounds.size

        if (firstOutbound == null) {
            return advanced(fallbackTitle)
        }

        val protocol = firstOutbound.string("protocol") ?: "xray"
        val tag = firstOutbound.string("tag")
        val settings = firstOutbound["settings"]?.jsonObjectOrNull()
        val streamSettings = firstOutbound["streamSettings"]?.jsonObjectOrNull()
        val endpoint = findEndpoint(settings)

        return ConnectionConfigSummary(
            title = tag?.takeIf { it.isNotBlank() }
                ?: endpoint?.address
                ?: if (outboundCount > 1) "Advanced Xray config" else fallbackTitle,
            protocol = protocol,
            address = endpoint?.address,
            port = endpoint?.port,
            security = streamSettings?.string("security")
                ?: findString(streamSettings, setOf("security", "tlsSettings", "realitySettings")),
            transport = streamSettings?.string("network"),
            sni = findString(streamSettings, setOf("serverName", "sni")),
            outboundCount = outboundCount,
            isAdvanced = outboundCount > 1 || endpoint == null,
        )
    }

    fun summarizeShareText(rawConfig: String): ConnectionConfigSummary {
        val firstLine = rawConfig.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val scheme = firstLine.substringBefore("://", missingDelimiterValue = "xray")
        val title = firstLine.substringAfter("#", missingDelimiterValue = "").takeIf { it.isNotBlank() }
        val hostPort = firstLine.substringAfter("@", missingDelimiterValue = "")
            .substringBefore("?")
            .substringBefore("#")
        val host = hostPort.substringBefore(":", missingDelimiterValue = "").takeIf { it.isNotBlank() }
        val port = hostPort.substringAfter(":", missingDelimiterValue = "").toIntOrNull()
        return ConnectionConfigSummary(
            title = title ?: host ?: "Xray config",
            protocol = scheme,
            address = host,
            port = port,
            security = queryValue(firstLine, "security"),
            transport = queryValue(firstLine, "type"),
            sni = queryValue(firstLine, "sni"),
            outboundCount = rawConfig.lineSequence().count { it.trim().contains("://") }.coerceAtLeast(1),
            isAdvanced = false,
        )
    }

    private fun advanced(title: String) = ConnectionConfigSummary(
        title = title,
        protocol = "xray",
        address = null,
        port = null,
        security = null,
        transport = null,
        sni = null,
        outboundCount = 0,
        isAdvanced = true,
    )

    private fun findEndpoint(element: JsonElement?): Endpoint? {
        return when (element) {
            is JsonObject -> {
                val direct = element.endpointOrNull()
                if (direct != null) return direct
                element.values.firstNotNullOfOrNull(::findEndpoint)
            }
            is JsonArray -> element.firstNotNullOfOrNull(::findEndpoint)
            else -> null
        }
    }

    private fun JsonObject.endpointOrNull(): Endpoint? {
        val address = string("address") ?: string("server")
        val port = int("port")
        return if (!address.isNullOrBlank() && port != null) Endpoint(address, port) else null
    }

    private fun findString(element: JsonElement?, keys: Set<String>): String? {
        return when (element) {
            is JsonObject -> {
                keys.firstNotNullOfOrNull { key -> element.string(key) }
                    ?: element.values.firstNotNullOfOrNull { value -> findString(value, keys) }
            }
            is JsonArray -> element.firstNotNullOfOrNull { value -> findString(value, keys) }
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(key: String): Int? {
        return this[key]?.jsonPrimitiveOrNull()?.intOrNull
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun queryValue(text: String, key: String): String? {
        val query = text.substringAfter("?", missingDelimiterValue = "")
            .substringBefore("#")
        return query.split("&")
            .firstOrNull { it.substringBefore("=") == key }
            ?.substringAfter("=", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
    }

    private data class Endpoint(
        val address: String,
        val port: Int,
    )
}
