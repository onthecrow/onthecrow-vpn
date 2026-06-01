import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    androidLibrary {
        namespace = "com.onthecrow.onthecrowvpn.firebase"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        val archSlice = when (iosTarget.name) {
            "iosArm64" -> "ios-arm64"
            "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
            else -> throw GradleException("Unknown iOS target architecture: ${iosTarget.name}")
        }

        val firebaseGroups = listOf(
            "FirebaseAnalytics",
            "FirebaseCrashlytics",
            "FirebaseFirestore",
        )

        fun getFrameworks(): List<Pair<String, String>> {
            val frameworks = mutableListOf<Pair<String, String>>()
            firebaseGroups.forEach { groupName ->
                val groupDir = rootProject.file("libs/$groupName")
                if (!groupDir.exists()) return@forEach

                groupDir.listFiles { file -> file.isDirectory && file.name.endsWith(".xcframework") }
                    ?.forEach { xcFramework ->
                        val sliceDir = File(xcFramework, archSlice)
                        if (sliceDir.exists()) {
                            frameworks += xcFramework.name.removeSuffix(".xcframework") to sliceDir.absolutePath
                        } else {
                            logger.warn("Warning: Slice '$archSlice' not found in ${xcFramework.name}")
                        }
                    }
            }
            return frameworks.distinctBy { it.first }
        }

        val allFrameworks = getFrameworks()

        iosTarget.compilations.getByName("main") {
            @Suppress("unused")
            val firebase by cinterops.creating {
                defFile(project.file("src/nativeInterop/cinterop/firebase.def"))
                allFrameworks.forEach { (_, path) ->
                    extraOpts("-compiler-option", "-F$path")
                }
            }
        }

        iosTarget.binaries.all {
            allFrameworks.forEach { (_, path) ->
                linkerOpts("-F$path")
            }
            allFrameworks.forEach { (name, _) ->
                linkerOpts("-framework", name)
            }
            linkerOpts(
                "-lsqlite3",
                "-lz",
                "-lc++",
                "-framework", "StoreKit",
                "-framework", "Foundation",
                "-framework", "UIKit",
                "-framework", "SystemConfiguration",
                "-framework", "Security",
                "-framework", "AdSupport",
                "-framework", "UserNotifications",
            )
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(projects.feature.connection.logicApi)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.analytics)
            implementation(libs.firebase.crashlytics)
            implementation(libs.firebase.firestore)
        }
    }
}
