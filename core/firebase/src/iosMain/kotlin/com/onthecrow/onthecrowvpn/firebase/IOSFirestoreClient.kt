package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.BundleResult
import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import com.onthecrow.onthecrowvpn.firebase.decodeBundleOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
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

    override fun observeBundle(bundleId: String): Flow<BundleResult> {
        if (FIRApp.defaultApp() == null) {
            return flowOf(BundleResult.Unavailable)
        }
        val firestore = FIRFirestore.firestore()
        return callbackFlow {
            val ref = firestore
                .collectionWithPath("configs")
                .documentWithPath(bundleId)
            val registration = ref.addSnapshotListener { snapshot, error ->
                when {
                    error != null -> {
                        trySend(BundleResult.Error(error.localizedDescription))
                    }
                    snapshot == null || !snapshot.exists -> {
                        trySend(BundleResult.NotFound)
                    }
                    else -> {
                        val raw = snapshot.data().orEmpty()
                        val typed = raw.entries
                            .mapNotNull { entry ->
                                val key = entry.key as? String ?: return@mapNotNull null
                                key to entry.value
                            }
                            .toMap()
                        val bundle = decodeBundleOrNull(bundleId, typed)
                        if (bundle == null) {
                            trySend(BundleResult.Error("Malformed bundle"))
                        } else {
                            trySend(BundleResult.Success(bundle))
                        }
                    }
                }
            }
            awaitClose { registration?.remove() }
        }
    }
}

private fun <K, V> Map<K, V>?.orEmpty(): Map<K, V> = this ?: emptyMap()
