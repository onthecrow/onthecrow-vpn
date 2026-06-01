package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Firebase.FIRApp
import platform.Firebase.FIRFirestore

internal actual fun createFirestoreClient(): FirestoreClient = IOSFirestoreClient()

@OptIn(ExperimentalForeignApi::class)
private class IOSFirestoreClient : FirestoreClient {
    override val isAvailable: Boolean
        get() {
            if (FIRApp.defaultApp() == null) return false
            FIRFirestore.firestore()
            return true
        }
}
