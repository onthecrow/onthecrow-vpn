package com.onthecrow.onthecrowvpn.settings

import androidx.compose.runtime.Composable

/** Version string for the footer, e.g. "1.0 (1)". Empty where the platform cannot report one. */
@Composable
internal expect fun appVersionLabel(): String

/**
 * Hands the diagnostic log to the system share sheet, or null where sharing is not wired up.
 *
 * Null is the signal the UI uses to decide whether the version row is long-pressable at all — an
 * inert long-press would be worse than none.
 */
@Composable
internal expect fun rememberLogSharer(): (() -> Unit)?

/** Opens a URL in the user's browser, or null where that is not wired up. */
@Composable
internal expect fun rememberUrlOpener(): ((String) -> Unit)?

/**
 * Where the privacy policy lives.
 *
 * Served by GitHub Pages straight out of `docs/privacy-policy.html` in this repository, so the
 * document that ships and the document that is published can never drift apart.
 */
internal const val PRIVACY_POLICY_URL = "https://onthecrow.github.io/onthecrow-vpn/privacy-policy.html"
