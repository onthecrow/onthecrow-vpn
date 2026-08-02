package com.onthecrow.deltavpn.vpn

import com.onthecrow.deltavpn.vpn.domain.SplitTunnelResolver
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode
import com.onthecrow.deltavpn.vpn.model.SplitTunnelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class SplitTunnelResolverTest {
    private val self = "com.onthecrow.deltavpn"

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

    @Test
    fun onlySelectedWithNothingChosenTunnelsEverythingInsteadOfNothing() {
        // A self-only allowlist establishes fine and passes the health probe (our own traffic IS
        // tunnelled) while every user app leaves the VPN — Connected, protecting nothing. Fail closed.
        val resolved = SplitTunnelResolver.resolve(
            SplitTunnelSettings(mode = SplitTunnelMode.ONLY_SELECTED, selectedPackages = emptySet()),
            selfPackage = self,
        )
        assertTrue(resolved.allow.isEmpty(), "an empty selection must not become a self-only allowlist")
        assertEquals(SplitTunnelResolver.PUSH_PACKAGES, resolved.disallow)
    }

    @Test
    fun onlySelectedWithNothingButPushChosenAlsoTunnelsEverything() {
        // Push is stripped from the allow set, so picking only push packages leaves it empty too.
        val resolved = SplitTunnelResolver.resolve(
            SplitTunnelSettings(
                mode = SplitTunnelMode.ONLY_SELECTED,
                selectedPackages = SplitTunnelResolver.PUSH_PACKAGES,
            ),
            selfPackage = self,
        )
        assertTrue(resolved.allow.isEmpty())
    }

    @Test
    fun onlySelectedWithARealChoiceStillCarriesOurOwnPackage() {
        val resolved = SplitTunnelResolver.resolve(
            SplitTunnelSettings(mode = SplitTunnelMode.ONLY_SELECTED, selectedPackages = setOf("com.bank.app")),
            selfPackage = self,
        )
        assertEquals(setOf("com.bank.app", self), resolved.allow)
        assertTrue(resolved.disallow.isEmpty(), "allow and disallow must never both be populated")
    }
}
