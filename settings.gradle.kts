rootProject.name = "OnthecrowVPN"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":composeApp")
include(":core:coroutines")
include(":core:datastore")
include(":core:navigation:api")
include(":core:navigation:impl")
include(":core:ui")
include(":core:ui-core")
include(":core:vpn:api")
include(":core:vpn:impl")
include(":core:xray")
include(":desktopApp")
include(":feature:connection:logic-api")
include(":feature:connection:logic-impl")
include(":feature:connection:ui-api")
include(":feature:connection:ui-impl")
