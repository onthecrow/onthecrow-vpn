package com.onthecrow.onthecrowvpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class XrayConfigSanitizer(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun sanitize(xrayJson: String): String {
        val parsed = runCatching { json.parseToJsonElement(xrayJson).jsonObject }.getOrNull()
            ?: return xrayJson
        val stripped = stripNulls(parsed) as? JsonObject ?: return xrayJson
        val outbounds = stripped["outbounds"] as? JsonArray
            ?: return json.encodeToString(JsonObject.serializer(), stripped)

        val sanitizedOutbounds = buildJsonArray {
            outbounds.forEach { entry ->
                if (entry is JsonObject) add(sanitizeOutbound(entry)) else add(entry)
            }
        }

        val sanitizedRoot = buildJsonObject {
            stripped.forEach { (key, value) ->
                if (key == "outbounds") put(key, sanitizedOutbounds) else put(key, value)
            }
        }
        return json.encodeToString(JsonObject.serializer(), sanitizedRoot)
    }

    private fun stripNulls(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (value !is JsonNull) put(key, stripNulls(value))
            }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(stripNulls(it)) }
        }
        else -> element
    }

    fun withTunInbound(
        xrayJson: String,
        name: String = "tun0",
        mtu: Int = 1500,
        logLevel: String? = null,
    ): String {
        val root = runCatching { json.parseToJsonElement(xrayJson).jsonObject }.getOrNull()
            ?: return xrayJson
        val tunInbound = buildJsonObject {
            put("port", 0)
            put("protocol", "tun")
            put("settings", buildJsonObject {
                put("name", name)
                put("MTU", mtu)
            })
        }
        val existingInbounds = root["inbounds"] as? JsonArray
        val mergedInbounds = buildJsonArray {
            add(tunInbound)
            existingInbounds?.forEach { add(it) }
        }
        val logBlock = logLevel?.let { buildJsonObject { put("loglevel", it) } }
        val newRoot = buildJsonObject {
            var inboundsWritten = false
            var logWritten = false
            root.forEach { (key, value) ->
                when (key) {
                    "inbounds" -> {
                        put(key, mergedInbounds)
                        inboundsWritten = true
                    }
                    "log" -> {
                        if (logBlock != null) {
                            put(key, logBlock)
                        } else {
                            put(key, value)
                        }
                        logWritten = true
                    }
                    else -> put(key, value)
                }
            }
            if (!inboundsWritten) put("inbounds", mergedInbounds)
            if (!logWritten && logBlock != null) put("log", logBlock)
        }
        return json.encodeToString(JsonObject.serializer(), newRoot)
    }

    private fun sanitizeOutbound(outbound: JsonObject): JsonObject {
        val sendThrough = outbound["sendThrough"]
        val keepSendThrough = sendThrough is JsonPrimitive &&
            sendThrough.contentOrNull?.let(::isIpLiteral) == true
        return buildJsonObject {
            outbound.forEach { (key, value) ->
                if (key == "sendThrough" && !keepSendThrough) return@forEach
                put(key, value)
            }
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        val stripped = value.substringBefore('%')
        return isIpv4(stripped) || isIpv6(stripped)
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isIpv6(value: String): Boolean {
        if (value.isEmpty() || value.count { it == ':' } < 2) return false
        val doubleColonCount = countOccurrences(value, "::")
        if (doubleColonCount > 1) return false

        val (head, tail) = if (doubleColonCount == 1) {
            value.substringBefore("::") to value.substringAfter("::")
        } else {
            value to ""
        }
        val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
        val tailGroups = if (tail.isEmpty()) emptyList() else tail.split(':')
        val totalGroups = headGroups.size + tailGroups.size

        val maxGroups = if (doubleColonCount == 1) 7 else 8
        if (totalGroups > maxGroups) return false
        if (doubleColonCount == 0 && totalGroups != 8) return false

        val allGroups = headGroups + tailGroups
        return allGroups.all(::isHexGroup)
    }

    private fun isHexGroup(group: String): Boolean {
        if (group.isEmpty() || group.length > 4) return false
        return group.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
    }

    private fun countOccurrences(text: String, substring: String): Int {
        var count = 0
        var index = text.indexOf(substring)
        while (index != -1) {
            count++
            index = text.indexOf(substring, index + substring.length)
        }
        return count
    }
}
