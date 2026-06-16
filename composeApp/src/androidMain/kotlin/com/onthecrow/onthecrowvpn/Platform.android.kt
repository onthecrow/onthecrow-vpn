package com.onthecrow.onthecrowvpn

import android.app.Application
import android.content.Context
import android.os.Build
import com.onthecrow.onthecrowvpn.firebase.AndroidFirebasePlatformContext
import com.onthecrow.onthecrowvpn.vpn.AndroidVpnEnvironment
import com.onthecrow.onthecrowvpn.vpn.log.DebugLog
import com.onthecrow.onthecrowvpn.xray.AndroidXrayEnvironment
import com.onthecrow.onthecrowvpn.xray.OtcLog
import org.koin.dsl.module

class AndroidPlatform(
    val application: Application,
) : Platform, AndroidFirebasePlatformContext {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val firebaseApplication: Application = application
    override val platformModule = module {
        single<Context> { application }
    }

    init {
        AndroidVpnEnvironment.initialize(application)
        AndroidXrayEnvironment.initialize(application)
        // TEMP (diagnosis): route common-code logs (worker, VM) into the unified file log.
        DebugLog.setSink { tag, message -> OtcLog.log(tag, message) }
        OtcLog.log("APP", "main process started — ${name}")
    }
}
