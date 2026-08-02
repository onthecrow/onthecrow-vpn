package com.onthecrow.deltavpn.vpn

/**
 * Pure, platform-agnostic helper for the Android tunnel-recovery health probe. Lives in commonMain ONLY
 * so it can be unit-tested without an Android device/runtime; the Android service (`DeltaVpnService`,
 * androidMain) is the sole caller.
 *
 * A minimal DNS A-query packet for [host] with [transactionId]. Used by the health probe: sending this
 * over the tunnel (UDP to 1.1.1.1:53) and getting ANY datagram back proves a real end-to-end round-trip
 * — unlike the old TCP-to-1.1.1.1:80 probe, which false-negatived because Cloudflare RSTs plain port 80.
 */
internal fun buildDnsQuery(host: String, transactionId: Int): ByteArray {
    val header = byteArrayOf(
        (transactionId ushr 8).toByte(), transactionId.toByte(),
        0x01, 0x00, // flags: standard query, recursion desired
        0x00, 0x01, // QDCOUNT = 1
        0x00, 0x00, // ANCOUNT
        0x00, 0x00, // NSCOUNT
        0x00, 0x00, // ARCOUNT
    )
    val qname = ArrayList<Byte>()
    for (label in host.split(".").filter { it.isNotEmpty() }) {
        val bytes = label.encodeToByteArray()
        qname.add(bytes.size.toByte())
        bytes.forEach { qname.add(it) }
    }
    qname.add(0.toByte()) // root label terminator
    val footer = byteArrayOf(0x00, 0x01, 0x00, 0x01) // QTYPE = A, QCLASS = IN
    return header + qname.toByteArray() + footer
}
