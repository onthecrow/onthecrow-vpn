# desktopApp (Compose Desktop / JVM)

The desktop launcher for OnthecrowVPN. It runs the **shared Compose Multiplatform UI** (`composeApp`)
on the JVM and binds the platform-specific VPN controller. The same connection screen, domain logic,
Firestore config loading and status handling are reused across Android / iOS / Desktop.

- Entry point: `desktopApp/src/main/kotlin/.../main.kt` (`application { … App() }`).
- UI + DI: `composeApp` (Koin, `ConnectionViewModel`, `ConnectionScreen`).

## VPN per platform (JVM)

The desktop `VpnController` actual is `PlatformVpnController.jvm.kt`
(`core/vpn/impl/src/jvmMain/.../`), which branches by host OS:

| Host | How the VPN runs |
|---|---|
| **macOS** | A real **NetworkExtension system VPN** (registered in System Settings). The **system extension + native bridge are embedded inside the one notarized `OnthecrowVPN.app`**; the JVM UI spawns the embedded bridge (`Contents/Helpers/`) over stdio via `MacosNeController`. Built/signed/notarized by `scripts/package-macos-app.sh`. See **[`../macosApp/README.md`](../macosApp/README.md)** for the full architecture, build & signing, and debugging. |
| **Windows** | Elevated **PowerShell + Wintun** sidecar (`vpn-windows.ps1`) running the libXray desktop binary; UAC prompt on connect. |
| Linux / other | Reports `VPN is not supported on this OS yet`. |

The config pipeline is identical everywhere: a share link (`vless://` / `hysteria2://`) is converted to
xray JSON (on desktop via the bundled `onthecrow-convert`, see `PlatformXrayEngine.jvm.kt`), the tun
inbound is injected and the config sanitized (`XrayConfigSanitizer`), then handed to the controller.

## Build & run

```bash
./gradlew :desktopApp:run                 # launch the app (dev)
./gradlew :desktopApp:createDistributable # build the (unsigned) app image
scripts/package-macos-app.sh              # (macOS) build + embed sysext/bridge + sign + notarize → one .app
```

### Prerequisites

- **Firebase (Firestore)** — loading configs by subscription id needs the desktop Firebase
  properties. See the firebase module's properties template + `.gitignore`’d local config
  (`core/firebase`, `JvmFirebaseConfig`). Without it the UI still launches but can't load bundles.
- **macOS VPN** — the production build is one notarized `OnthecrowVPN.app` with the system extension +
  bridge embedded, produced by `scripts/package-macos-app.sh` (Developer ID + `notarytool`; runs with
  **SIP enabled**). **Follow [`../macosApp/README.md`](../macosApp/README.md) §5.** A `:desktopApp:run`
  dev session falls back to the SIP-off dev service app (§5.8).
- **Windows VPN** — build the libXray desktop sidecar into `local-libs/libxray-desktop/` and provide
  `wintun.dll` (see `scripts/build-libxray-desktop.sh` and `DesktopVpnSupport`).

### Bundled runtime resources

`prepareDesktopVpnResources` copies the per-OS sidecar/converter (`local-libs/libxray-desktop/<os-arch>/`)
and wrapper scripts into `desktopApp/resources/<os-arch>/`, exposed at runtime via
`System.getProperty("compose.application.resources.dir")` and resolved by `DesktopVpnSupport`.

> macOS distribution is fully automated by `scripts/package-macos-app.sh`: it embeds the system
> extension + bridge into the jpackage `OnthecrowVPN.app`, deep-signs Developer ID, notarizes and
> staples — a single double-clickable bundle that activates the extension with SIP on (one-time approval
> in System Settings). No separate `/Applications` service app.
