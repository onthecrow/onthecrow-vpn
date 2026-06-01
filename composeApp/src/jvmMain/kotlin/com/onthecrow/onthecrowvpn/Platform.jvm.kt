package com.onthecrow.onthecrowvpn

import org.koin.dsl.module

class JvmPlatform : Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val platformModule = module {}
}
