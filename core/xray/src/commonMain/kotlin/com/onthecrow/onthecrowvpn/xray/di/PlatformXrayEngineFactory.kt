package com.onthecrow.onthecrowvpn.xray.di

import com.onthecrow.onthecrowvpn.xray.XrayEngine

/**
 * How this platform reaches the xray engine.
 *
 * A seam rather than a direct `PlatformXrayEngine()` because on Android the engine does not live in
 * the process that asks for it: libXray is hosted in `:xray`, and the app process talks to it over a
 * binder. Every other platform returns the engine itself.
 */
internal expect fun createXrayEngine(): XrayEngine
