package com.onthecrow.deltavpn

import com.onthecrow.deltavpn.firebase.FirebasePlatformContext
import org.koin.core.module.Module

interface Platform : FirebasePlatformContext {
    val name: String
    val platformModule: Module
}
