import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The Crashlytics-ONLY Firebase surface for the iOS Network-Extension tunnel (the `PacketTunnel` appex).
//
// core:firebase links the full Firebase graph (Analytics + Crashlytics + Firestore) — Firestore drags in
// grpcpp/gRPC/absl/leveldb/openssl_grpc, which the packet-tunnel appex (a tight memory budget, and no
// Firestore use) must never carry. This module binds ONLY the Crashlytics dependency closure, so the NE
// can report crashes without any of that. It never references anything under libs/FirebaseFirestore.
//
// Apple-only, matching the NE's targets. iOS binds the Crashlytics xcframeworks directly (cinterop under
// package `platform.FirebaseCrashlytics` — deliberately distinct from core:firebase's `platform.Firebase`
// so the two cinterops can never clash; they are never on the same binary anyway). macOS binds through
// the GitLive KMP SDK, same as core:firebase's macosMain (macOS has no grpcpp problem).
kotlin {
    // The exact Crashlytics link closure, split across the two vendor zips Google ships them in.
    // NOTHING from libs/FirebaseFirestore. Keep in sync with the frameworks linked into the PacketTunnel
    // target in iosApp.xcodeproj (see docs/error-reporting.md).
    val crashlyticsFrameworks = mapOf(
        // Crashlytics-specific (Google's FirebaseCrashlytics zip)
        "FirebaseCrashlytics" to "FirebaseCrashlytics",
        "FirebaseSessions" to "FirebaseCrashlytics",
        "FirebaseCoreExtension" to "FirebaseCrashlytics",
        "FirebaseRemoteConfigInterop" to "FirebaseCrashlytics",
        "GoogleDataTransport" to "FirebaseCrashlytics",
        "Promises" to "FirebaseCrashlytics",
        // Shared Firebase "core" set (Google ships these in the FirebaseAnalytics zip)
        "FirebaseCore" to "FirebaseAnalytics",
        "FirebaseCoreInternal" to "FirebaseAnalytics",
        "FirebaseInstallations" to "FirebaseAnalytics",
        "GoogleUtilities" to "FirebaseAnalytics",
        "nanopb" to "FirebaseAnalytics",
        "FBLPromises" to "FirebaseAnalytics",
    )

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        val archSlice = when (iosTarget.name) {
            "iosArm64" -> "ios-arm64"
            "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
            else -> throw GradleException("Unknown iOS target architecture: ${iosTarget.name}")
        }

        // Resolve each framework's slice dir; warn (don't fail) on a missing slice so a partial libs/
        // checkout still configures.
        val sliceDirs: List<Pair<String, String>> = crashlyticsFrameworks.mapNotNull { (name, group) ->
            val slice = rootProject.file("libs/$group/$name.xcframework/$archSlice")
            if (slice.exists()) {
                name to slice.absolutePath
            } else {
                logger.warn("Warning: Crashlytics slice '$archSlice' not found for $name in libs/$group")
                null
            }
        }

        iosTarget.compilations.getByName("main") {
            @Suppress("unused")
            val crashlytics by cinterops.creating {
                defFile(project.file("src/nativeInterop/cinterop/crashlytics.def"))
                sliceDirs.forEach { (_, path) ->
                    extraOpts("-compiler-option", "-F$path")
                }
            }
        }

        iosTarget.binaries.all {
            sliceDirs.forEach { (_, path) -> linkerOpts("-F$path") }
            sliceDirs.forEach { (name, _) -> linkerOpts("-framework", name) }
            linkerOpts(
                "-lz",
                "-lc++",
                "-framework", "Foundation",
                "-framework", "Security",
                "-framework", "SystemConfiguration",
            )
        }
    }

    macosArm64()

    sourceSets {
        commonMain.dependencies {
            // Owns the CrashReporter port + the high-level ErrorReporter this module builds on top of it.
            // `api` so consumers (core:vpn:ios-tunnel) get ErrorReporter/ErrorDomain transitively.
            api(projects.core.errorReporting)
        }
        macosMain.dependencies {
            implementation(libs.firebase.kotlin.app)
            implementation(libs.firebase.kotlin.crashlytics)
        }
    }
}
