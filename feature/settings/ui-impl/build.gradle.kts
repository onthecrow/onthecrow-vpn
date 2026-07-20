plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    androidLibrary {
        namespace = "com.onthecrow.onthecrowvpn.settings.ui.impl"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        androidMain.dependencies {
            // FileProvider, for handing the log file to the share sheet.
            implementation(libs.androidx.core.ktx)
            // OtcLog owns the shared log file: its path, and the flush that must happen before sharing.
            implementation(projects.core.xray)
        }
        commonMain.dependencies {
            implementation(projects.core.ui)
            implementation(projects.core.uiCore)
            implementation(projects.core.navigation.api)
            implementation(projects.core.vpn.api)
            implementation(projects.feature.settings.uiApi)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
