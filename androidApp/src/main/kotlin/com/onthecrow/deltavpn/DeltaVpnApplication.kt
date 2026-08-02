package com.onthecrow.deltavpn

import android.app.Application
import android.os.Build
import java.io.File

class DeltaVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Application.onCreate runs in EVERY process of the app, and one of ours — `:xray` — exists
        // precisely so it can be killed and replaced cheaply. Bringing Koin and Firebase up in it would
        // pay for the whole app graph on every engine restart, and put objects with process-global
        // state in the process we throw away. It initialises the little it needs in
        // XrayEngineService.onCreate instead.
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
