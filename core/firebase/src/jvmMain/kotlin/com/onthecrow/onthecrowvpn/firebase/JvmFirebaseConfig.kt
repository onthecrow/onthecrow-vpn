package com.onthecrow.onthecrowvpn.firebase

import java.io.File
import java.util.Properties

internal data class FirebaseDesktopConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val storageBucket: String?,
    val gcmSenderId: String?,
)

/**
 * Looks for a `firebase-admin.properties` file. Returns `null` if missing or any
 * required field is blank — that's the signal for `JvmFirebaseInitializer` to skip
 * Firebase setup and leave clients running in no-op mode.
 *
 * Lookup chain (first hit wins):
 *   1. `-Dfirebase.config=/abs/path/to/firebase-admin.properties` (JVM system property)
 *   2. `<compose.application.resources.dir>/firebase-admin.properties` (packaged/installed app)
 *   3. `./firebase-admin.properties` (current working directory)
 *   4. `./desktopApp/firebase-admin.properties` (repo root, dev run)
 */
internal object JvmFirebaseConfig {
    fun load(): FirebaseDesktopConfig? {
        val candidates = buildList {
            System.getProperty("firebase.config")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            // Bundled into the packaged app via appResourcesRootDir (so an installed copy works offline of the repo).
            System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }
                ?.let { add(File(it, "firebase-admin.properties")) }
            add(File("firebase-admin.properties"))
            add(File("desktopApp/firebase-admin.properties"))
        }
        val file = candidates.firstOrNull { it.exists() } ?: return null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val projectId = props.getProperty("firebase.projectId")?.takeIf { it.isNotBlank() } ?: return null
        val applicationId = props.getProperty("firebase.applicationId")?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = props.getProperty("firebase.apiKey")?.takeIf { it.isNotBlank() } ?: return null
        return FirebaseDesktopConfig(
            projectId = projectId,
            applicationId = applicationId,
            apiKey = apiKey,
            storageBucket = props.getProperty("firebase.storageBucket")?.takeIf { it.isNotBlank() },
            gcmSenderId = props.getProperty("firebase.gcmSenderId")?.takeIf { it.isNotBlank() },
        )
    }
}
