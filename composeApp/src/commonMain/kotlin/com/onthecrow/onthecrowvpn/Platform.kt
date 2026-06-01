package com.onthecrow.onthecrowvpn

import com.onthecrow.onthecrowvpn.firebase.FirebasePlatformContext
import org.koin.core.module.Module

interface Platform : FirebasePlatformContext {
    val name: String
    val platformModule: Module
}
