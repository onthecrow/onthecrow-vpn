package com.onthecrow.deltavpn.firebase

import kotlinx.coroutines.flow.Flow

interface FirestoreClient {
    val isAvailable: Boolean
    fun observeBundle(bundleId: String): Flow<BundleResult>
}
