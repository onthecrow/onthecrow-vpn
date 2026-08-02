package com.onthecrow.deltavpn.settings

import androidx.compose.runtime.Composable

@Composable
internal actual fun appVersionLabel(): String = ""

// No file logger and no share sheet here — the version row stays inert.
@Composable
internal actual fun rememberLogSharer(): (() -> Unit)? = null

@Composable
internal actual fun rememberUrlOpener(): ((String) -> Unit)? = null
