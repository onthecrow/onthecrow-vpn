package com.onthecrow.onthecrowvpn.firebase

import android.app.Application
import com.google.firebase.FirebaseApp as JavaFirebaseApp
import com.google.firebase.FirebaseOptions as JavaFirebaseOptions
import com.google.firebase.FirebasePlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap

actual object FirebaseInitializer {
    actual fun initialize(context: FirebasePlatformContext) {
        if (JvmFirebaseRuntime.isReady) return
        val config = JvmFirebaseConfig.load()
        if (config == null) {
            println("[Firebase] firebase-admin.properties not found. Desktop Firebase disabled.")
            return
        }

        // gitlive's firebase-java-sdk requires a FirebasePlatform installed before any
        // Firebase call (token storage + on-disk db root). The in-memory store + tmpdir
        // approach is fine for an unauthenticated read-only client.
        if (!platformInstalled) {
            FirebasePlatform.initializeFirebasePlatform(InMemoryFirebasePlatform())
            platformInstalled = true
        }

        val options = JavaFirebaseOptions.Builder()
            .setProjectId(config.projectId)
            .setApplicationId(config.applicationId)
            .setApiKey(config.apiKey)
            .apply {
                config.storageBucket?.let(::setStorageBucket)
                config.gcmSenderId?.let(::setGcmSenderId)
            }
            .build()

        val app = Application()
        if (JavaFirebaseApp.getApps(app).isEmpty()) {
            JavaFirebaseApp.initializeApp(app, options)
        }

        JvmFirebaseRuntime.isReady = true
        println("[Firebase] initialised for project ${config.projectId}")
    }

    @Volatile
    private var platformInstalled: Boolean = false
}

internal object JvmFirebaseRuntime {
    @Volatile
    var isReady: Boolean = false
}

private class InMemoryFirebasePlatform : FirebasePlatform() {
    private val store = ConcurrentHashMap<String, String>()
    private val dbRoot = File(System.getProperty("java.io.tmpdir"), "onthecrowvpn-firebase").also { it.mkdirs() }

    override fun store(key: String, value: String) { store[key] = value }
    override fun retrieve(key: String): String? = store[key]
    override fun clear(key: String) { store.remove(key) }
    override fun log(msg: String) { println("[firebase] $msg") }
    override fun getDatabasePath(name: String): File = File(dbRoot, name)
}
