package com.onthecrow.onthecrowvpn.firebase

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

actual object FirebaseInitializer {
    actual fun initialize(context: FirebasePlatformContext) {
        val application = (context as? AndroidFirebasePlatformContext)?.firebaseApplication
        AndroidFirebaseEnvironment.initialize(application)
    }
}

internal object AndroidFirebaseEnvironment {
    private const val TAG = "OnthecrowFirebase"

    private var application: Application? = null

    fun initialize(application: Application?) {
        if (application == null) {
            Log.w(TAG, "Firebase initialization skipped: Android application context is missing.")
            return
        }

        this.application = application
        if (FirebaseApp.getApps(application).isNotEmpty()) return

        val app = FirebaseApp.initializeApp(application)
        if (app == null) {
            Log.w(TAG, "Firebase config is missing. Firebase features are disabled for this build.")
        }
    }

    fun getApplication(): Application? = application

    fun isConfigured(): Boolean {
        val application = application ?: return false
        return FirebaseApp.getApps(application).isNotEmpty()
    }
}
