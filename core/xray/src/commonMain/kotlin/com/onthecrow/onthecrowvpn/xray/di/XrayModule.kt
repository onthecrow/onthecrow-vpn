package com.onthecrow.onthecrowvpn.xray.di

import com.onthecrow.onthecrowvpn.xray.PlatformXrayEngine
import com.onthecrow.onthecrowvpn.xray.XrayConfigSummarizer
import com.onthecrow.onthecrowvpn.xray.XrayEngine
import org.koin.dsl.bind
import org.koin.dsl.module

val xrayModule = module {
    single { XrayConfigSummarizer() }
    single { PlatformXrayEngine() } bind XrayEngine::class
}
