package com.onthecrow.deltavpn

import android.app.Application
import android.content.Context
import android.os.Build
import com.onthecrow.deltavpn.firebase.AndroidFirebasePlatformContext
import com.onthecrow.deltavpn.vpn.AndroidVpnEnvironment
import com.onthecrow.deltavpn.vpn.log.DebugLog
import com.onthecrow.deltavpn.xray.AndroidXrayEnvironment
import com.onthecrow.deltavpn.xray.OtcLog
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
        // Route the common-code VPN logs (VpnSyncWorker, which owns live-tunnel config switches) into
        // the same file log the service writes. Scoped to the tunnel — general app/UI logging
        // deliberately does not go through here.
        DebugLog.setSink { tag, message -> OtcLog.log(tag, message) }
    }
}
