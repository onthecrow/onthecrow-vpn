package com.onthecrow.onthecrowvpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

internal class RecoveryHelpersTest {

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
