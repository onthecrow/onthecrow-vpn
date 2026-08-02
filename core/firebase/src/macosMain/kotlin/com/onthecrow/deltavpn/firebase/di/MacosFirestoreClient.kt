package com.onthecrow.deltavpn.firebase.di

import com.onthecrow.deltavpn.firebase.BundleResult
import com.onthecrow.deltavpn.firebase.FirestoreClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * No-op Firestore on macOS. The Firestore client is consumed only by the connection feature, whose
 * module does not target macOS — this actual exists purely to satisfy the `createFirestoreClient`
 * expect for the macOS target. Wire GitLive `firebase-firestore` here if a macOS surface ever needs it.
 */
internal actual fun createFirestoreClient(): FirestoreClient = MacosFirestoreClient

private object MacosFirestoreClient : FirestoreClient {
    override val isAvailable: Boolean = false
    override fun observeBundle(bundleId: String): Flow<BundleResult> = flowOf(BundleResult.Unavailable)
}
