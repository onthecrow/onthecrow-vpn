package com.onthecrow.onthecrowvpn.connection.data.subscription

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parses an x-ui/v2rayN-style subscription payload: usually base64(share links joined by \n), but
 * some panels serve plain text. Forgiving: base64 is tried first (whitespace stripped), and the
 * decode only wins if it actually looks like links — otherwise the body is treated as plain text.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object SubscriptionPayloadParser {
    // scheme://rest — accepts any share-link scheme (vless, hysteria2, trojan, ss, …).
    private val LINK_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://\S+$""")

    fun parseLinks(body: String): List<String> {
        val decoded = runCatching {
            Base64.Mime.decode(body.filterNot(Char::isWhitespace)).decodeToString()
        }.getOrNull()?.takeIf { it.contains("://") }
        return (decoded ?: body)
            .lineSequence()
            .map(String::trim)
            .filter { LINK_REGEX.matches(it) }
            .toList()
    }

    /** 3x-ui sends `profile-title: base64:<b64>`; other panels send a plain value. */
    fun decodeProfileTitle(header: String?): String? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val value = if (raw.startsWith(BASE64_PREFIX)) {
            runCatching { Base64.decode(raw.removePrefix(BASE64_PREFIX)).decodeToString() }.getOrNull()
        } else {
            raw
        }
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private const val BASE64_PREFIX = "base64:"
}
