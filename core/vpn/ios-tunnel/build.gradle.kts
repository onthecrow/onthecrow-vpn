plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
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

        iosTarget.binaries.framework {
            baseName = "OnthecrowTunnel"
            isStatic = true
        }

        iosTarget.binaries.all {
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
