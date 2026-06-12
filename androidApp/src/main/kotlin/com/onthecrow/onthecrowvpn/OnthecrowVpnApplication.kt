package com.onthecrow.onthecrowvpn

import android.app.Application
import android.os.Build
import java.io.File

class OnthecrowVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The VpnService runs in a separate ":vpn" process so it can be killed on disconnect — that
        // clears xray-core's PROCESS-GLOBAL hysteria connection pool (keyed by server address), which an
        // in-process stop/start does NOT clear and which otherwise makes a config switch reuse the
        // previous client's authenticated QUIC session.
        //
        // Application.onCreate runs in BOTH processes; only the main UI process brings up the full app
        // graph (Koin/Firebase). The :vpn process hosts only the service + libXray and self-initializes
        // the small env it needs in OnthecrowVpnService.onCreate.
        if (isMainProcess()) {
            AppInitializer.initialize(AndroidPlatform(this))
        }
    }

    private fun isMainProcess(): Boolean {
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim { it <= ' ' } }.getOrNull()
        }
        return current == null || current == packageName
    }
}
