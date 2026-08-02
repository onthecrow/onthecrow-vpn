package com.onthecrow.deltavpn.xray.di

import com.onthecrow.deltavpn.xray.PlatformXrayEngine
import com.onthecrow.deltavpn.xray.XrayEngine

internal actual fun createXrayEngine(): XrayEngine = PlatformXrayEngine()
