package com.onthecrow.onthecrowvpn.firebase

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Firebase.FIRApp
import platform.Foundation.NSBundle
import platform.Foundation.NSLog

@OptIn(ExperimentalForeignApi::class)
actual object FirebaseInitializer {
    actual fun initialize(context: FirebasePlatformContext) {
        if (FIRApp.defaultApp() != null) return

        val configPath = NSBundle.mainBundle.pathForResource(
            name = "GoogleService-Info",
            ofType = "plist",
        )
        if (configPath == null) {
            NSLog("Firebase config is missing. Firebase features are disabled for this build.")
            return
        }

        FIRApp.configure()
    }
}
