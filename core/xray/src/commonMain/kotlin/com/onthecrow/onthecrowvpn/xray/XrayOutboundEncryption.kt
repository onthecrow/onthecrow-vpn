package com.onthecrow.onthecrowvpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Refuses a configuration whose outbound would carry the user's traffic in the clear.
 *
 * ### Why this exists
 * The tun takes the WHOLE device. Whatever the first outbound is becomes the only path for every app
 * on the phone, so a share link that converts to a plain SOCKS hop does not merely "not help" — it
 * publishes the user's entire traffic to their network, while the app's own first-run disclosure is on
 * screen promising an *encrypted* tunnel, and while a Play encryption attestation says the same. Xray
 * itself is happy to dial any of these; nothing else in this app looked at the protocol.
 *
 * ### The rule
 * Reject only what is **demonstrably** cleartext, and allow anything unrecognised. A false rejection
 * takes a working tunnel away from the user; a false acceptance is caught by nothing. So the list below
 * is deliberately narrow and every entry is a protocol that carries no encryption of its own:
 *
 * - `socks` / `http` — plaintext proxy hops by definition.
 * - `vless` / `trojan` — no crypto in the protocol; they rely entirely on `streamSettings.security`
 *   (`tls`, `reality`, `xtls`). Without it the payload is on the wire.
 * - `shadowsocks` with method `none` / `plain` / `dummy` — the explicit no-cipher methods.
 * - `vmess` with user cipher `none` / `zero` **and** no transport security — vmess normally encrypts
 *   itself, so only the explicit no-cipher settings qualify.
 *
 * Everything else — `hysteria`/`hysteria2` (QUIC, always TLS), `wireguard`, and any protocol added to
 * xray after this was written — passes untouched.
 */
object XrayOutboundEncryption {

    private val json = Json { ignoreUnknownKeys = true }

    /** Protocols that provide no confidentiality unless the transport supplies it. */
    private val TRANSPORT_SECURED_PROTOCOLS = setOf("vless", "trojan")

    /** Values of `streamSettings.security` that actually encrypt. */
    private val SECURE_TRANSPORTS = setOf("tls", "reality", "xtls")

    /** Shadowsocks methods that mean "no cipher". */
    private val NULL_CIPHERS = setOf("none", "plain", "dummy")

    /** vmess user ciphers that mean "no cipher". */
    private val NULL_VMESS_CIPHERS = setOf("none", "zero")

    /**
     * @return null when the primary outbound encrypts, or a short user-facing reason when it does not.
     *   Malformed or unreadable JSON returns null: this gate exists to catch a specific, checkable
     *   mistake, and everything else in `validate()` is already better at rejecting broken configs.
     */
    fun cleartextReason(xrayJson: String): String? {
        val outbound = primaryOutbound(xrayJson) ?: return null
        val protocol = outbound.string("protocol")?.lowercase() ?: return null
        val security = ((outbound["streamSettings"] as? JsonObject)?.string("security"))?.lowercase()
        val transportEncrypts = security in SECURE_TRANSPORTS

        return when {
            protocol == "socks" || protocol == "http" ->
                "This server uses a plain $protocol proxy, which would send all of your traffic unencrypted."

            protocol in TRANSPORT_SECURED_PROTOCOLS && !transportEncrypts ->
                "This ${protocol.uppercase()} server has no TLS or REALITY security, " +
                    "which would send all of your traffic unencrypted."

            protocol == "shadowsocks" && shadowsocksMethod(outbound) in NULL_CIPHERS && !transportEncrypts ->
                "This Shadowsocks server is configured without a cipher, " +
                    "which would send all of your traffic unencrypted."

            protocol == "vmess" && vmessCipher(outbound) in NULL_VMESS_CIPHERS && !transportEncrypts ->
                "This VMess server is configured without encryption, " +
                    "which would send all of your traffic unencrypted."

            else -> null
        }
    }

    /** The proxy outbound is the first one — the same convention [XrayConfigSummarizer] reads. */
    private fun primaryOutbound(xrayJson: String): JsonObject? = runCatching {
        val root = json.parseToJsonElement(xrayJson).jsonObject
        (root["outbounds"] as? JsonArray)?.firstOrNull() as? JsonObject
    }.getOrNull()

    /** `settings.servers[].method` */
    private fun shadowsocksMethod(outbound: JsonObject): String? {
        val servers = (outbound["settings"] as? JsonObject)?.get("servers") as? JsonArray
        return (servers?.firstOrNull() as? JsonObject)?.string("method")?.lowercase()
    }

    /** `settings.vnext[].users[].security` */
    private fun vmessCipher(outbound: JsonObject): String? {
        val vnext = (outbound["settings"] as? JsonObject)?.get("vnext") as? JsonArray
        val users = (vnext?.firstOrNull() as? JsonObject)?.get("users") as? JsonArray
        return (users?.firstOrNull() as? JsonObject)?.string("security")?.lowercase()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
