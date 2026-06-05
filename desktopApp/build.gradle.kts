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
            packageName = "com.onthecrow.onthecrowvpn"
            packageVersion = "1.0.0"

            // Bundles the VPN engine into the packaged app. Compose merges
            // `<resources>/common`, `<os>` and `<os>-<arch>` into the runtime dir
            // exposed via System.getProperty("compose.application.resources.dir").
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
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
    doLast {
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
