package com.onthecrow.deltavpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ConnectionParamsTest {

    @Test
    fun roundTripsMultiLineConfigAndPackageLists() {
        val params = ConnectionParams(
            xrayJson = "{\n  \"inbounds\": [],\n  \"outbounds\": []\n}",
            disallow = listOf("com.google.android.gms", "com.google.android.gsf"),
            allow = emptyList(),
        )
        assertEquals(params, decodeConnectionParams(encodeConnectionParams(params)))
    }

    @Test
    fun roundTripsBothListsPopulated() {
        val params = ConnectionParams("xray", listOf("a.b"), listOf("c.d", "e.f"))
        assertEquals(params, decodeConnectionParams(encodeConnectionParams(params)))
    }

    @Test
    fun decodeRejectsMalformedOrEmptyConfig() {
        assertNull(decodeConnectionParams("")) // no header lines
        assertNull(decodeConnectionParams("disallow\nallow\n")) // blank xrayJson
        assertNull(decodeConnectionParams("only one line"))
    }
}
