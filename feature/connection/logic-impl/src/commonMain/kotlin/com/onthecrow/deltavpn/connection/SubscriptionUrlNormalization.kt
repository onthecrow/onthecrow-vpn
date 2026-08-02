package com.onthecrow.deltavpn.connection

import io.ktor.http.Url

/**
 * Canonical form of a subscription URL, used ONLY for duplicate detection (the original, as-pasted
 * URL is what we store and fetch with). Folds away cosmetic differences that still address the same
 * endpoint, without touching anything semantically significant:
 *
 *  - scheme + host lowercased (both are case-insensitive per RFC 3986);
 *  - the default port for the scheme dropped (`:443` for https / `:80` for http), non-default kept;
 *  - trailing slashes on the path trimmed;
 *  - fragment dropped; query kept verbatim (param order/values can be significant — never reordered).
 *
 * Deliberately NOT content-based: a subscription's payload rotates over time, so it is not a stable
 * identity — two adds of the same URL on different days must still collide, and two different URLs
 * that momentarily return the same nodes must not. The URL is the stable identity.
 *
 * Falls back to the trimmed input if the string doesn't parse as a URL (the caller has already
 * ensured an http(s) scheme by this point).
 */
internal fun normalizeSubscriptionUrl(raw: String): String {
    val trimmed = raw.trim()
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return trimmed
    val host = url.host.lowercase()
    val portPart = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
    val path = url.encodedPath.trimEnd('/')
    val query = if (url.encodedQuery.isEmpty()) "" else "?${url.encodedQuery}"
    return "${url.protocol.name.lowercase()}://$host$portPart$path$query"
}
