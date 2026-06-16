package com.onthecrow.onthecrowvpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class RecoveryHelpersTest {

    @Test
    fun firstRecoveryAttemptIsImmediateThenBacksOff() {
        assertEquals(0L, recoveryDelayMs(0)) // attempt 0 fires immediately
        assertEquals(1_000L, recoveryDelayMs(1))
        assertEquals(2_000L, recoveryDelayMs(2))
        assertEquals(4_000L, recoveryDelayMs(3))
    }

    @Test
    fun fastAttemptsStayUnderTheUnacceptableCeiling() {
        // Sum of inter-attempt delays across all fast attempts must be well under the 20s "unacceptable"
        // bar even before counting probe windows.
        val totalDelay = (0 until MAX_FAST_ATTEMPTS).sumOf { recoveryDelayMs(it) }
        assertTrue(totalDelay <= 7_000L, "total backoff $totalDelay ms too high")
    }

    @Test
    fun dnsQueryHasCorrectHeaderQuestionAndFooter() {
        val q = buildDnsQuery("a.bc", 0x1234)
        // header(12) + qname(1,'a',2,'b','c',0 = 6) + footer(4) = 22
        assertEquals(22, q.size)
        assertEquals(0x12.toByte(), q[0]); assertEquals(0x34.toByte(), q[1]) // transaction id
        assertEquals(0x01.toByte(), q[2]); assertEquals(0x00.toByte(), q[3]) // flags: RD
        assertEquals(0x00.toByte(), q[4]); assertEquals(0x01.toByte(), q[5]) // QDCOUNT = 1
        // QNAME
        assertEquals(1.toByte(), q[12]); assertEquals('a'.code.toByte(), q[13])
        assertEquals(2.toByte(), q[14]); assertEquals('b'.code.toByte(), q[15]); assertEquals('c'.code.toByte(), q[16])
        assertEquals(0.toByte(), q[17]) // root terminator
        // footer: QTYPE = A (1), QCLASS = IN (1)
        assertEquals(0x00.toByte(), q[18]); assertEquals(0x01.toByte(), q[19])
        assertEquals(0x00.toByte(), q[20]); assertEquals(0x01.toByte(), q[21])
    }
}
