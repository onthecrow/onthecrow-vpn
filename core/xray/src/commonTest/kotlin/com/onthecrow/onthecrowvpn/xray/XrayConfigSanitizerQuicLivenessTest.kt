package com.onthecrow.onthecrowvpn.xray

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The QUIC liveness block is what bounds how long a dead path costs the user, and it is merged into a
 * structure the share-link converter also writes to (bandwidth, port hopping, obfuscation). Losing
 * someone's port hopping to a tuning change would be silent and would only show up as a server that
 * stops working, so the merge is pinned down here.
 */
class XrayConfigSanitizerQuicLivenessTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sanitizer = XrayConfigSanitizer(json)
    private val liveness = XrayConfigSanitizer.QuicLiveness(
        maxIdleTimeoutSeconds = 10,
        keepAlivePeriodSeconds = 3,
    )

    private fun quicParamsOf(config: String, index: Int = 0): JsonObject? =
        json.parseToJsonElement(config).jsonObject["outbounds"]!!.jsonArray[index]
            .jsonObject["streamSettings"]?.jsonObject
            ?.get("finalmask")?.jsonObject
            ?.get("quicParams")?.jsonObject

    @Test
    fun `adds liveness to a hysteria outbound that has no finalmask at all`() {
        val config = """
            {"outbounds":[{"protocol":"hysteria","streamSettings":{"network":"hysteria"}}]}
        """.trimIndent()

        val quic = quicParamsOf(sanitizer.withTunInbound(config, quicLiveness = liveness))

        assertEquals(10, quic!!["maxIdleTimeout"]!!.jsonPrimitive.int)
        assertEquals(3, quic["keepAlivePeriod"]!!.jsonPrimitive.int)
    }

    @Test
    fun `keeps bandwidth and port hopping the share link already carried`() {
        val config = """
            {"outbounds":[{"protocol":"hysteria","streamSettings":{"network":"hysteria",
            "finalmask":{"udp":[{"type":"obfs"}],
            "quicParams":{"brutalUp":"50mbps","brutalDown":"200mbps",
            "udpHop":{"ports":"20000-30000","interval":{"from":30,"to":30}}}}}}]}
        """.trimIndent()

        val result = sanitizer.withTunInbound(config, quicLiveness = liveness)
        val quic = quicParamsOf(result)!!
        val finalMask = json.parseToJsonElement(result).jsonObject["outbounds"]!!.jsonArray[0]
            .jsonObject["streamSettings"]!!.jsonObject["finalmask"]!!.jsonObject

        assertEquals("50mbps", quic["brutalUp"]!!.jsonPrimitive.content)
        assertEquals("200mbps", quic["brutalDown"]!!.jsonPrimitive.content)
        assertTrue(quic["udpHop"] is JsonObject, "port hopping must survive the merge")
        assertTrue(finalMask["udp"] is JsonArray, "obfuscation masks must survive the merge")
        assertEquals(10, quic["maxIdleTimeout"]!!.jsonPrimitive.int)
        assertEquals(3, quic["keepAlivePeriod"]!!.jsonPrimitive.int)
    }

    @Test
    fun `never overrides values the config already states`() {
        val config = """
            {"outbounds":[{"protocol":"hysteria","streamSettings":{"network":"hysteria",
            "finalmask":{"quicParams":{"maxIdleTimeout":60,"keepAlivePeriod":25}}}}]}
        """.trimIndent()

        val quic = quicParamsOf(sanitizer.withTunInbound(config, quicLiveness = liveness))!!

        assertEquals(60, quic["maxIdleTimeout"]!!.jsonPrimitive.int)
        assertEquals(25, quic["keepAlivePeriod"]!!.jsonPrimitive.int)
    }

    @Test
    fun `leaves non-QUIC outbounds untouched`() {
        val config = """
            {"outbounds":[{"protocol":"vless","streamSettings":{"network":"tcp"}}]}
        """.trimIndent()

        assertNull(quicParamsOf(sanitizer.withTunInbound(config, quicLiveness = liveness)))
    }

    @Test
    fun `writes nothing when no liveness is asked for`() {
        val config = """
            {"outbounds":[{"protocol":"hysteria","streamSettings":{"network":"hysteria"}}]}
        """.trimIndent()

        assertNull(quicParamsOf(sanitizer.withTunInbound(config)))
    }

    /** Out-of-range values make xray-core reject the WHOLE config, so they are clamped, not passed on. */
    @Test
    fun `clamps values to the range xray-core accepts`() {
        val config = """
            {"outbounds":[{"protocol":"hysteria","streamSettings":{"network":"hysteria"}}]}
        """.trimIndent()
        val absurd = XrayConfigSanitizer.QuicLiveness(maxIdleTimeoutSeconds = 1, keepAlivePeriodSeconds = 999)

        val quic = quicParamsOf(sanitizer.withTunInbound(config, quicLiveness = absurd))!!

        assertEquals(4, quic["maxIdleTimeout"]!!.jsonPrimitive.int)
        assertEquals(60, quic["keepAlivePeriod"]!!.jsonPrimitive.int)
    }
}
