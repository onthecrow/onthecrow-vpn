package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.firebase.BundleResult
import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import com.onthecrow.onthecrowvpn.firebase.JvmFirebaseRuntime
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

internal actual fun createFirestoreClient(): FirestoreClient = JvmFirestoreClient

private object JvmFirestoreClient : FirestoreClient {
    override val isAvailable: Boolean
        get() = JvmFirebaseRuntime.isReady

    override fun observeBundle(bundleId: String): Flow<BundleResult> {
        if (!JvmFirebaseRuntime.isReady) {
            return flowOf(BundleResult.Unavailable)
        }
        val doc = Firebase.firestore.collection("configs").document(bundleId)
        return doc.snapshots
            .map { snap ->
                if (!snap.exists) {
                    BundleResult.NotFound
                } else {
                    runCatching {
                        snap.data(BundleDocDto.serializer())
                    }.fold(
                        onSuccess = { dto ->
                            BundleResult.Success(
                                ConfigBundle(
                                    id = snap.id,
                                    name = dto.name?.takeIf { it.isNotBlank() },
                                    createdAt = dto.createdAt,
                                    updatedAt = dto.updatedAt.takeIf { it > 0L } ?: dto.createdAt,
                                    configs = dto.configs,
                                ),
                            )
                        },
                        onFailure = { BundleResult.Error("Malformed bundle: ${it.message}") },
                    )
                }
            }
            .catch { emit(BundleResult.Error(it.message ?: "Firestore error")) }
    }
}

@Serializable
private data class BundleDocDto(
    val name: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val configs: List<RemoteConfig> = emptyList(),
)
