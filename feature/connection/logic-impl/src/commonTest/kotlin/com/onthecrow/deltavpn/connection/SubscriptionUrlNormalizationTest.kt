package com.onthecrow.deltavpn.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class SubscriptionUrlNormalizationTest {

    private fun same(a: String, b: String) =
        assertEquals(normalizeSubscriptionUrl(a), normalizeSubscriptionUrl(b), "expected duplicates: $a / $b")

    private fun different(a: String, b: String) =
        assertNotEquals(normalizeSubscriptionUrl(a), normalizeSubscriptionUrl(b), "expected distinct: $a / $b")

    @Test
    fun trailingSlashIsIgnored() =
        same("https://x.com/sub/abc", "https://x.com/sub/abc/")

    @Test
    fun schemeAndHostAreCaseInsensitive() =
        same("https://Sub.Example.COM/sub/abc", "https://sub.example.com/sub/abc")

    @Test
    fun explicitDefaultPortEqualsImplicit() {
        same("https://x.com:443/sub/abc", "https://x.com/sub/abc")
        same("http://x.com:80/sub/abc", "http://x.com/sub/abc")
    }

    @Test
    fun surroundingWhitespaceIsIgnored() =
        same("  https://x.com/sub/abc  ", "https://x.com/sub/abc")

    @Test
    fun nonDefaultPortIsSignificant() =
        different("https://onthecrow.mooo.com:2096/sub/abc", "https://onthecrow.mooo.com/sub/abc")

    @Test
    fun differentPathsAreDistinct() =
        different("https://x.com/sub/abc", "https://x.com/sub/def")

    @Test
    fun queryIsPreservedAndSignificant() {
        same("https://x.com/sub?token=1", "https://x.com/sub/?token=1")
        different("https://x.com/sub?token=1", "https://x.com/sub?token=2")
    }
}
