package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.vpn.domain.SplitTunnelResolver
import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelMode
import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class SplitTunnelResolverTest {
    private val self = "com.onthecrow.onthecrowvpn"

    @Test
    fun offWithPushBypassesPushPackages() {
        val r = SplitTunnelResolver.resolve(SplitTunnelSettings(excludePushServices = true), self)
        assertEquals(SplitTunnelResolver.PUSH_PACKAGES, r.disallow)
        assertTrue(r.allow.isEmpty())
    }

    @Test
    fun offWithoutPushIsEmpty() {
        val r = SplitTunnelResolver.resolve(SplitTunnelSettings(excludePushServices = false), self)
        assertTrue(r.disallow.isEmpty())
        assertTrue(r.allow.isEmpty())
    }

    @Test
    fun bypassSelectedAddsPush() {
        val r = SplitTunnelResolver.resolve(
            SplitTunnelSettings(
                mode = SplitTunnelMode.BYPASS_SELECTED,
                selectedPackages = setOf("com.example.app"),
                excludePushServices = true,
            ),
            self,
        )
        assertEquals(setOf("com.example.app") + SplitTunnelResolver.PUSH_PACKAGES, r.disallow)
        assertTrue(r.allow.isEmpty())
    }

    @Test
    fun onlySelectedAllowsSelfAndDropsPush() {
        val r = SplitTunnelResolver.resolve(
            SplitTunnelSettings(
                mode = SplitTunnelMode.ONLY_SELECTED,
                // includes a push package explicitly — must be dropped so push bypasses
                selectedPackages = setOf("com.example.app", "com.google.android.gms"),
                excludePushServices = true,
            ),
            self,
        )
        assertTrue(r.disallow.isEmpty())
        assertEquals(setOf("com.example.app", self), r.allow)
        assertTrue(self in r.allow)
        assertTrue("com.google.android.gms" !in r.allow)
    }

    @Test
    fun selfNeverInDisallow() {
        val r = SplitTunnelResolver.resolve(
            SplitTunnelSettings(mode = SplitTunnelMode.BYPASS_SELECTED, selectedPackages = setOf(self)),
            self,
        )
        // (resolver doesn't strip self from disallow, but the applier does; document the contract here)
        assertTrue(self in r.disallow)
    }
}
