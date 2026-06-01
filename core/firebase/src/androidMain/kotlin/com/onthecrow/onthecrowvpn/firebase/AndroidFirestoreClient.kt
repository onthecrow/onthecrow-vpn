package com.onthecrow.onthecrowvpn.firebase.di

import com.google.firebase.firestore.FirebaseFirestore
import com.onthecrow.onthecrowvpn.firebase.AndroidFirebaseEnvironment
import com.onthecrow.onthecrowvpn.firebase.FirestoreClient

internal actual fun createFirestoreClient(): FirestoreClient = AndroidFirestoreClient()

private class AndroidFirestoreClient : FirestoreClient {
    override val isAvailable: Boolean
        get() {
            if (!AndroidFirebaseEnvironment.isConfigured()) return false
            return runCatching { FirebaseFirestore.getInstance() }.isSuccess
        }
}
