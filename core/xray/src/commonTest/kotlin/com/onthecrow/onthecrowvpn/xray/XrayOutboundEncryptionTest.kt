package com.onthecrow.onthecrowvpn.xray

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XrayOutboundEncryptionTest {

    private fun config(outbound: String) = """{"outbounds":[$outbound]}"""

    // ---- must be REFUSED: the tun would carry the whole device in the clear ----

    @Test
    fun plainSocksIsRefused() {
        assertNotNull(XrayOutboundEncryption.cleartextReason(config("""{"protocol":"socks"}""")))
    }

    @Test
    fun plainHttpIsRefused() {
        assertNotNull(XrayOutboundEncryption.cleartextReason(config("""{"protocol":"http"}""")))
    }

    @Test
    fun vlessWithoutTransportSecurityIsRefused() {
        assertNotNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"vless","streamSettings":{"security":"none"}}"""),
            ),
        )
        // security absent entirely is the same thing
        assertNotNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"vless","streamSettings":{"network":"tcp"}}"""),
            ),
        )
    }

    @Test
    fun trojanWithoutTransportSecurityIsRefused() {
        assertNotNull(
            XrayOutboundEncryption.cleartextReason(config("""{"protocol":"trojan"}""")),
        )
    }

    @Test
    fun shadowsocksWithNullCipherIsRefused() {
        assertNotNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"shadowsocks","settings":{"servers":[{"method":"none"}]}}"""),
            ),
        )
    }

    @Test
    fun vmessWithoutEncryptionIsRefused() {
        assertNotNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"vmess","settings":{"vnext":[{"users":[{"security":"none"}]}]}}"""),
            ),
        )
    }

    // ---- must be ALLOWED: a false rejection takes a working tunnel away ----

    @Test
    fun hysteriaIsAllowed() {
        // The app's own default outbound. QUIC is always TLS, and it declares no `security` field —
        // exactly the shape that a naive "security must be tls" rule would wrongly reject.
        assertNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"hysteria","streamSettings":{"network":"hysteria"}}"""),
            ),
        )
    }

    @Test
    fun vlessWithTlsOrRealityIsAllowed() {
        assertNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"vless","streamSettings":{"security":"tls"}}"""),
            ),
        )
        assertNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"vless","streamSettings":{"security":"reality"}}"""),
            ),
        )
    }

    @Test
    fun shadowsocksWithARealCipherIsAllowed() {
        assertNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"shadowsocks","settings":{"servers":[{"method":"2022-blake3-aes-128-gcm"}]}}"""),
            ),
        )
    }

    @Test
    fun vmessWithItsOwnCipherIsAllowed() {
        assertNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"vmess","settings":{"vnext":[{"users":[{"security":"auto"}]}]}}"""),
            ),
        )
    }

    @Test
    fun unknownProtocolsAreAllowed() {
        // Anything xray gains after this was written must not be blocked by a stale allow-list.
        assertNull(XrayOutboundEncryption.cleartextReason(config("""{"protocol":"wireguard"}""")))
        assertNull(XrayOutboundEncryption.cleartextReason(config("""{"protocol":"something-new"}""")))
    }

    @Test
    fun malformedOrEmptyConfigIsNotOurProblem() {
        // `validate()` has better tools for a broken config; this gate answers one question only.
        assertNull(XrayOutboundEncryption.cleartextReason("not json"))
        assertNull(XrayOutboundEncryption.cleartextReason("""{"outbounds":[]}"""))
        assertNull(XrayOutboundEncryption.cleartextReason("{}"))
    }

    @Test
    fun onlyThePrimaryOutboundIsJudged() {
        // A `freedom`/`blackhole` tail is normal in a converted config and must not trip the gate,
        // and a cleartext outbound sitting behind the proxy is not what carries the user's traffic.
        assertNull(
            XrayOutboundEncryption.cleartextReason(
                config("""{"protocol":"vless","streamSettings":{"security":"reality"}},{"protocol":"freedom"}"""),
            ),
        )
    }
}
