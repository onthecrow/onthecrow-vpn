package com.onthecrow.onthecrowvpn.vpn

/**
 * Pure, platform-agnostic helpers for the Android tunnel-recovery state machine. They live in
 * commonMain ONLY so they can be unit-tested without an Android device/runtime; the Android service
 * (`OnthecrowVpnService`, androidMain) is the sole caller.
 */

/** Max fast recovery attempts before falling back to the slower keepalive watch. */
internal const val MAX_FAST_ATTEMPTS: Int = 4

/**
 * Delay BEFORE recovery attempt [attempt] (0-based). Attempt 0 fires immediately (0 ms) — on a good
 * network the first re-establish should already succeed; later attempts back off 1s/2s/4s to let the
 * radio/route settle. Keeps the happy path ~1-3s and the worst case (all fast attempts) well under 20s.
 */
internal fun recoveryDelayMs(attempt: Int): Long = if (attempt <= 0) 0L else 500L shl attempt

/**
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
