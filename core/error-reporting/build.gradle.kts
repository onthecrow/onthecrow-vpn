import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    androidLibrary {
        namespace = "com.onthecrow.deltavpn.errorreporting"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            // No Firebase dependency on purpose. This module OWNS the `CrashReporter` port and the
            // high-level `ErrorReporter`; the concrete Crashlytics/stdout binding is supplied by a
            // consumer (core:firebase for the app, core:firebase-crashlytics for the iOS NE). Depending
            // on core:firebase here would drag its full Firebase graph (Analytics/Firestore/gRPC) into
            // every Apple consumer of error-reporting — including the NE appex — which is exactly what
            // the crash-only split exists to avoid.
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
