package com.onthecrow.deltavpn.vpn

/**
 * The minimal set of parameters needed to (re)establish a tunnel WITHOUT re-validating over the network.
 * Persisted by the Android service so the `:vpn` process can self-reconnect after a crash / system kill
 * (see `ConnectionParamsStore`, androidMain). The codec below is pure (commonMain) so it is unit-testable.
 */
internal data class ConnectionParams(
    val xrayJson: String,
    val disallow: List<String>,
    val allow: List<String>,
)

/**
 * Serialize without any extra dependency: two header lines (the disallow/allow package lists — package
 * names never contain commas or newlines) followed by the raw, verbatim [ConnectionParams.xrayJson]
 * (which may itself span lines). [decodeConnectionParams] reverses it with `split(limit = 3)`.
 */
internal fun encodeConnectionParams(params: ConnectionParams): String = buildString {
    append(params.disallow.joinToString(",")); append('\n')
    append(params.allow.joinToString(",")); append('\n')
    append(params.xrayJson)
}

internal fun decodeConnectionParams(text: String): ConnectionParams? {
    val parts = text.split("\n", limit = 3)
    if (parts.size < 3) return null
    val xrayJson = parts[2]
    if (xrayJson.isBlank()) return null
    return ConnectionParams(
        xrayJson = xrayJson,
        disallow = parts[0].split(",").filter { it.isNotBlank() },
        allow = parts[1].split(",").filter { it.isNotBlank() },
    )
}
