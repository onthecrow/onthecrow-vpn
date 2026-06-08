plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
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

        appleTarget.binaries.framework {
            baseName = "OnthecrowTunnel"
            isStatic = true
        }

        appleTarget.binaries.all {
            if (sliceDir.exists()) {
                linkerOpts("-F${sliceDir.absolutePath}", "-framework", "LibXray")
            }
            linkerOpts(
                "-framework", "Foundation",
                "-framework", "Security",
                "-framework", "SystemConfiguration",
                "-framework", "NetworkExtension",
                "-lresolv",
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.xray)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
