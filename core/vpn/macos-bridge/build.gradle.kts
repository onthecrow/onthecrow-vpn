plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // A tiny native helper executable, embedded (signed) inside the macOS .app bundle. The JVM
    // desktop app spawns it and talks to it over stdio. It owns the NetworkExtension system-VPN
    // profile (NETunnelProviderManager) and the system-extension activation (OSSystemExtensionRequest)
    // — both Obj-C APIs the JVM can't call directly. All the NE plumbing is reused from
    // :core:vpn:impl (AppleTunnelManager, shared with iOS).
    macosArm64 {
        binaries.executable {
            baseName = "onthecrow-macos-bridge"
            entryPoint = "com.onthecrow.onthecrowvpn.macosbridge.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.vpn.impl)
            implementation(projects.core.vpn.api)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
