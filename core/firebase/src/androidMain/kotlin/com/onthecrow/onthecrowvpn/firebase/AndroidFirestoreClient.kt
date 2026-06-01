package com.onthecrow.onthecrowvpn.firebase.di

import com.google.firebase.firestore.FirebaseFirestore
import com.onthecrow.onthecrowvpn.firebase.AndroidFirebaseEnvironment
import com.onthecrow.onthecrowvpn.firebase.BundleResult
import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import com.onthecrow.onthecrowvpn.firebase.decodeBundleOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

internal actual fun createFirestoreClient(): FirestoreClient = AndroidFirestoreClient()

private class AndroidFirestoreClient : FirestoreClient {
    override val isAvailable: Boolean
        get() {
            if (!AndroidFirebaseEnvironment.isConfigured()) return false
            return runCatching { FirebaseFirestore.getInstance() }.isSuccess
        }

    override fun observeBundle(bundleId: String): Flow<BundleResult> {
        if (!AndroidFirebaseEnvironment.isConfigured()) {
            return flowOf(BundleResult.Unavailable)
        }
        val firestore = runCatching { FirebaseFirestore.getInstance() }.getOrElse {
            return flowOf(BundleResult.Error(it.message ?: "Firestore unavailable"))
        }
        return callbackFlow {
            val registration = firestore.collection("configs").document(bundleId)
                .addSnapshotListener { snapshot, error ->
                    when {
                        error != null -> {
                            trySend(BundleResult.Error(error.message ?: "Firestore error"))
                        }
                        snapshot == null || !snapshot.exists() -> {
                            trySend(BundleResult.NotFound)
                        }
                        else -> {
                            val bundle = decodeBundleOrNull(bundleId, snapshot.data)
                            if (bundle == null) {
                                trySend(BundleResult.Error("Malformed bundle"))
                            } else {
                                trySend(BundleResult.Success(bundle))
                            }
                        }
                    }
                }
            awaitClose { registration.remove() }
        }
    }
}
