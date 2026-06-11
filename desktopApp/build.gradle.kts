import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.composeApp)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.onthecrow.onthecrowvpn.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OnthecrowVPN"
            packageVersion = "1.0.0"
            description = "OnthecrowVPN"
            vendor = "Onthecrow"

            // Bundle the FULL JDK module set. jlink otherwise trims the runtime to a handful of modules,
            // which breaks the Firebase/Firestore/gRPC stack at runtime (it needs java.naming for DNS
            // resolution, java.sql for the local cache, etc.) — the app initialises Firebase but then
            // can't fetch /configs/{id}. includeAllModules avoids guessing the exact set (~+40 MB).
            includeAllModules = true

            // Bundles the VPN engine into the packaged app. Compose merges
            // `<resources>/common`, `<os>` and `<os>-<arch>` into the runtime dir
            // exposed via System.getProperty("compose.application.resources.dir").
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))

            macOS {
                // Must equal the App ID the Developer ID provisioning profiles are issued for, so the
                // embedded NE system extension (child id `…SystemExtension`) and the bridge sign cleanly.
                // NOTE: we deliberately do NOT enable Compose's built-in signing here — the production
                // build is signed/notarized by scripts/package-macos-app.sh, which also embeds the
                // system extension + bridge (each with their own entitlements/profile). See
                // macosApp/README.md §5.
                bundleID = "com.onthecrow.onthecrowvpn"
                iconFile.set(project.file("icons/OnthecrowVPN.icns"))
            }

            windows {
                // Create Start-menu + desktop shortcuts so it installs like a normal app, and let the
                // user pick the install location. The fixed upgradeUuid lets future versions upgrade in
                // place instead of installing side-by-side.
                menuGroup = "OnthecrowVPN"
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "a69d5b2a-21d4-4d80-a7ff-0ecd70ec5125"
                iconFile.set(project.file("icons/OnthecrowVPN.ico"))
            }

            linux {
                iconFile.set(project.file("icons/OnthecrowVPN.png"))
            }
        }
    }
}

// Assembles desktopApp/resources/<os-arch>/ from the built sidecar/converter
// (local-libs, gitignored) plus the privileged wrapper scripts, so the packaged
// distributable carries everything PlatformVpnController needs at runtime.
val prepareDesktopVpnResources by tasks.registering {
    val libRoot = rootProject.layout.projectDirectory.dir("local-libs/libxray-desktop").asFile
    val scriptsDir = rootProject.layout.projectDirectory.dir("scripts/desktop").asFile
    val resRoot = layout.projectDirectory.dir("resources").asFile
    val firebaseProps = layout.projectDirectory.file("firebase-admin.properties").asFile
    doLast {
        // Bundle the Firebase web config into resources/common so an INSTALLED app (whose working dir
        // is the install folder, not the repo) can still load configs by subscription id. The config is
        // a publicly-readable web config (Firestore rules restrict access), so shipping it is fine.
        if (firebaseProps.exists()) {
            val common = File(resRoot, "common").apply { mkdirs() }
            firebaseProps.copyTo(File(common, "firebase-admin.properties"), overwrite = true)
        } else {
            logger.warn("firebase-admin.properties not found — the packaged app won't be able to load Firebase configs.")
        }
        if (!libRoot.exists()) {
            logger.warn("local-libs/libxray-desktop not found — run scripts/build-libxray-desktop.sh first.")
            return@doLast
        }
        libRoot.listFiles()?.filter { it.isDirectory }?.forEach { archDir ->
            val dest = File(resRoot, archDir.name).apply { mkdirs() }
            archDir.listFiles()?.forEach { f -> f.copyTo(File(dest, f.name), overwrite = true) }
            val wrapper = if (archDir.name.startsWith("windows")) {
                File(scriptsDir, "vpn-windows.ps1")
            } else {
                File(scriptsDir, "vpn-macos.sh")
            }
            if (wrapper.exists()) wrapper.copyTo(File(dest, wrapper.name), overwrite = true)
        }
    }
}

// Populate desktopApp/resources BEFORE Compose collects it into the app image.
afterEvaluate {
    tasks.matching { it.name == "prepareAppResources" }.configureEach {
        dependsOn(prepareDesktopVpnResources)
    }
}
