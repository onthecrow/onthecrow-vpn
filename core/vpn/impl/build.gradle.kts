plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    androidLibrary {
        namespace = "com.onthecrow.onthecrowvpn.vpn.impl"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            // ApplicationScopeProvider for the split-tunnel sync (Android-only; core/coroutines has no macOS target).
            implementation(projects.core.coroutines)
            // Analytics for the VPN-service events. androidMain ONLY: core:analytics has no macOS
            // target, and OnthecrowVpnService (the only caller) is androidMain anyway.
            implementation(projects.core.analytics)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(projects.core.vpn.api)
            implementation(projects.core.xray)
            implementation(projects.feature.connection.logicApi)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            // Crashlytics error reporting for caught non-fatals, all targets (macOS = no-op).
            implementation(projects.core.errorReporting)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
