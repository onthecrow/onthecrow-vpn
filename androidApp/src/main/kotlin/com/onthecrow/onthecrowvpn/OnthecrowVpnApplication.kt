package com.onthecrow.onthecrowvpn

import android.app.Application

class OnthecrowVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInitializer.initialize(AndroidPlatform(this))
    }
}
