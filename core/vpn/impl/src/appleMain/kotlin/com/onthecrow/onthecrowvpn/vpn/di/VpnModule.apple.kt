package com.onthecrow.onthecrowvpn.vpn.di

import org.koin.core.module.Module
import org.koin.dsl.module

// Split tunneling is Android-only for now.
actual val vpnPlatformModule: Module = module { }
