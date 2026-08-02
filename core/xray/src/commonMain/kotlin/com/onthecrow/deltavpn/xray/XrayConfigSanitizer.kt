package com.onthecrow.deltavpn.xray

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

    /**
     * How quickly a QUIC-based outbound is allowed to notice that its path has died.
     *
     * Both values are seconds, and both are bounded by xray-core's own validation: `maxIdleTimeout`
     * 4..120, `keepAlivePeriod` 2..60. Outside those it refuses the whole config, so they are clamped.
     */
    data class QuicLiveness(val maxIdleTimeoutSeconds: Int, val keepAlivePeriodSeconds: Int)

    fun withTunInbound(
        xrayJson: String,
        name: String = "tun0",
        mtu: Int = 1500,
        logLevel: String? = null,
        errorLogPath: String? = null,
        quicLiveness: QuicLiveness? = null,
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
        val logBlock = if (logLevel != null || errorLogPath != null) {
            buildJsonObject {
                if (logLevel != null) put("loglevel", logLevel)
                // Write xray's error log to a file (e.g. the App Group container) so a sandboxed
                // packet-tunnel extension's diagnostics are readable from outside.
                if (errorLogPath != null) put("error", errorLogPath)
            }
        } else {
            null
        }
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
                    // Some share-link converters leak the config remark into outbound.sendThrough,
                    // which Xray rejects ("unable to send through: <remark>"). Drop any non-IP value.
                    "outbounds" -> put(key, withQuicLiveness(sanitizeOutbounds(value), quicLiveness))
                    else -> put(key, value)
                }
            }
            if (!inboundsWritten) put("inbounds", mergedInbounds)
            if (!logWritten && logBlock != null) put("log", logBlock)
        }
        return json.encodeToString(JsonObject.serializer(), newRoot)
    }

    /**
     * Tell QUIC-based outbounds how fast to give up on a dead path.
     *
     * With xray-core's defaults a hysteria2 client keeps writing into the QUIC session bound to an
     * interface that no longer exists until the idle timeout expires — 30s by default, and measured in
     * the field at 13-22s of AWAKE time, because the timer is a Go timer on CLOCK_MONOTONIC and stops
     * during CPU suspend. Nothing reconnects before then: the first request AFTER the timeout is what
     * dials again. That interval, not our recovery ladder, is what sets how long a handover or a Doze
     * exit leaves the user without traffic.
     *
     * Upstream also leaves `KeepAlivePeriod` at zero (the default is commented out in its dialer), so
     * QUIC sends no keepalives at all. That is why the two go together: keepalives stop a healthy but
     * quiet connection from idling out under a shortened timeout, and the shortened timeout is what
     * kills a dead one quickly.
     *
     * MERGED, never assigned. The share-link converter already writes `finalmask.quicParams` when the
     * link carries bandwidth or port-hopping parameters, and `finalmask.udp` for obfuscation —
     * replacing the block would silently drop port hopping for anyone using it. Existing values win:
     * this only fills in what is absent.
     *
     * Scoped to `streamSettings.network == "hysteria"`. The field is read by the QUIC dialer only, so
     * setting it elsewhere would be inert rather than harmful, but a config that says only what it
     * means is one fewer thing to explain later.
     */
    private fun withQuicLiveness(value: JsonElement, liveness: QuicLiveness?): JsonElement {
        if (liveness == null) return value
        val array = value as? JsonArray ?: return value
        return buildJsonArray {
            array.forEach { entry ->
                val outbound = entry as? JsonObject
                val stream = outbound?.get("streamSettings") as? JsonObject
                val network = (stream?.get("network") as? JsonPrimitive)?.contentOrNull
                if (outbound == null || stream == null || network != HYSTERIA_NETWORK) {
                    add(entry)
                    return@forEach
                }
                val finalMask = stream[FINAL_MASK_KEY] as? JsonObject
                val quicParams = finalMask?.get(QUIC_PARAMS_KEY) as? JsonObject
                val mergedQuic = buildJsonObject {
                    quicParams?.forEach { (k, v) -> put(k, v) }
                    if (quicParams?.get("maxIdleTimeout") == null) {
                        put("maxIdleTimeout", liveness.maxIdleTimeoutSeconds.coerceIn(4, 120))
                    }
                    if (quicParams?.get("keepAlivePeriod") == null) {
                        put("keepAlivePeriod", liveness.keepAlivePeriodSeconds.coerceIn(2, 60))
                    }
                }
                val mergedMask = buildJsonObject {
                    finalMask?.forEach { (k, v) -> if (k != QUIC_PARAMS_KEY) put(k, v) }
                    put(QUIC_PARAMS_KEY, mergedQuic)
                }
                val mergedStream = buildJsonObject {
                    stream.forEach { (k, v) -> if (k != FINAL_MASK_KEY) put(k, v) }
                    put(FINAL_MASK_KEY, mergedMask)
                }
                add(
                    buildJsonObject {
                        outbound.forEach { (k, v) -> if (k != "streamSettings") put(k, v) }
                        put("streamSettings", mergedStream)
                    },
                )
            }
        }
    }

    private fun sanitizeOutbounds(value: JsonElement): JsonElement {
        val array = value as? JsonArray ?: return value
        return buildJsonArray {
            array.forEach { entry ->
                if (entry is JsonObject) add(sanitizeOutbound(entry)) else add(entry)
            }
        }
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

    private companion object {
        // What the share-link converter emits for hysteria2: both outbound.protocol and
        // streamSettings.network are "hysteria", with the version carried inside hysteriaSettings.
        const val HYSTERIA_NETWORK = "hysteria"

        // Lowercase, no camelCase — this is the literal json tag on xray-core's StreamConfig.
        const val FINAL_MASK_KEY = "finalmask"
        const val QUIC_PARAMS_KEY = "quicParams"
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
