package com.onthecrow.onthecrowvpn.xray.di

import com.onthecrow.onthecrowvpn.xray.PlatformXrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayEngine

internal actual fun createXrayEngine(): XrayEngine = PlatformXrayEngine()
