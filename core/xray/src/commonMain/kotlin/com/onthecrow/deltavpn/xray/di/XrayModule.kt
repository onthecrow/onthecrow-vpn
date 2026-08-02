package com.onthecrow.deltavpn.xray.di

import com.onthecrow.deltavpn.xray.XrayConfigSummarizer
import com.onthecrow.deltavpn.xray.XrayEngine
import org.koin.dsl.bind
import org.koin.dsl.module

val xrayModule = module {
    single { XrayConfigSummarizer() }
    single { createXrayEngine() } bind XrayEngine::class
}
