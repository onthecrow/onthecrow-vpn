package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.connection.data.subscription.SubscriptionPayloadParser
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
internal class SubscriptionPayloadParserTest {
    private val links = listOf(
        "vless://uuid@1.2.3.4:443?security=tls#Server%20A",
        "hysteria2://auth@example.com:1935?sni=example.com#Server-B",
    )

    @Test
    fun parsesBase64Payload() {
        val payload = Base64.encode(links.joinToString("\n").encodeToByteArray())
        assertEquals(links, SubscriptionPayloadParser.parseLinks(payload))
    }

    @Test
    fun parsesBase64PayloadWithLineBreaks() {
        // MIME-style base64 with wrapped lines (some panels wrap at 76 chars).
        val payload = Base64.encode(links.joinToString("\n").encodeToByteArray())
            .chunked(40)
            .joinToString("\n")
        assertEquals(links, SubscriptionPayloadParser.parseLinks(payload))
    }

    @Test
    fun parsesPlainTextPayload() {
        val payload = links.joinToString("\n") + "\n\n# comment line\n"
        assertEquals(links, SubscriptionPayloadParser.parseLinks(payload))
    }

    @Test
    fun garbageYieldsEmptyList() {
        assertTrue(SubscriptionPayloadParser.parseLinks("<!DOCTYPE html><html>login page</html>").isEmpty())
        assertTrue(SubscriptionPayloadParser.parseLinks("").isEmpty())
        assertTrue(SubscriptionPayloadParser.parseLinks("just some words").isEmpty())
    }

    @Test
    fun decodesPlainProfileTitle() {
        assertEquals("My subscription", SubscriptionPayloadParser.decodeProfileTitle("My subscription"))
    }

    @Test
    fun decodesBase64ProfileTitle() {
        val encoded = "base64:" + Base64.encode("Моя подписка".encodeToByteArray())
        assertEquals("Моя подписка", SubscriptionPayloadParser.decodeProfileTitle(encoded))
    }

    @Test
    fun blankOrMissingProfileTitleIsNull() {
        assertNull(SubscriptionPayloadParser.decodeProfileTitle(null))
        assertNull(SubscriptionPayloadParser.decodeProfileTitle("  "))
    }
}
