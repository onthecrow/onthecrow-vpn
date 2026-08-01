package com.onthecrow.onthecrowvpn.firebase

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.initialize

/**
 * macOS Firebase bootstrap via the GitLive KMP SDK. `Firebase.initialize()` maps to `FIRApp.configure()`
 * on Apple, reading `GoogleService-Info.plist` from the running bundle — so for reports to actually send
 * from a macOS binary (the sysext / bridge), that plist must be embedded in its bundle and the Firebase
 * frameworks linked. Wrapped in runCatching so a missing config disables Firebase instead of crashing.
 */
actual object FirebaseInitializer {
    actual fun initialize(context: FirebasePlatformContext) {
        runCatching { Firebase.initialize() }
    }
}
