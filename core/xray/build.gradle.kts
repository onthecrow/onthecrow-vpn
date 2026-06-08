import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    androidLibrary {
        namespace = "com.onthecrow.onthecrowvpn.xray"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
    ).forEach { appleTarget ->
        val archSlice = when (appleTarget.name) {
            "iosArm64" -> "ios-arm64"
            "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
            "macosArm64" -> "macos-arm64_x86_64"
            else -> throw GradleException("Unknown Apple target: ${appleTarget.name}")
        }
        val sliceDir = rootProject.file("libs/LibXray/LibXray.xcframework/$archSlice")

        appleTarget.compilations.getByName("main") {
            @Suppress("unused")
            val libxray by cinterops.creating {
                defFile(project.file("src/nativeInterop/cinterop/libxray.def"))
                extraOpts("-compiler-option", "-fmodules")
                if (sliceDir.exists()) extraOpts("-compiler-option", "-F${sliceDir.absolutePath}")
            }
        }

        appleTarget.binaries.all {
            if (sliceDir.exists()) {
                linkerOpts("-F${sliceDir.absolutePath}", "-framework", "LibXray")
            }
            linkerOpts(
                "-framework", "Foundation",
                "-framework", "Security",
                "-framework", "SystemConfiguration",
                "-lresolv",
            )
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.connection.logicApi)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
