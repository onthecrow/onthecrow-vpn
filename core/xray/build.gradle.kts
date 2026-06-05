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
    ).forEach { iosTarget ->
        val archSlice = when (iosTarget.name) {
            "iosArm64" -> "ios-arm64"
            "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
            else -> throw GradleException("Unknown iOS target: ${iosTarget.name}")
        }
        val sliceDir = rootProject.file("libs/LibXray/LibXray.xcframework/$archSlice")

        iosTarget.compilations.getByName("main") {
            @Suppress("unused")
            val libxray by cinterops.creating {
                defFile(project.file("src/nativeInterop/cinterop/libxray.def"))
                extraOpts("-compiler-option", "-fmodules")
                if (sliceDir.exists()) extraOpts("-compiler-option", "-F${sliceDir.absolutePath}")
            }
        }

        iosTarget.binaries.all {
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
