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
| **macOS** | A real **NetworkExtension system VPN** (registered in System Settings). The JVM app spawns the provisioned **`OnthecrowVpnService.app`** and talks to it over stdio via `MacosNeController`. **The JVM process itself needs no signing/entitlements.** See **[`../macosApp/README.md`](../macosApp/README.md)** for the full architecture, build & signing, and debugging. |
| **Windows** | Elevated **PowerShell + Wintun** sidecar (`vpn-windows.ps1`) running the libXray desktop binary; UAC prompt on connect. |
| Linux / other | Reports `VPN is not supported on this OS yet`. |

The config pipeline is identical everywhere: a share link (`vless://` / `hysteria2://`) is converted to
xray JSON (on desktop via the bundled `onthecrow-convert`, see `PlatformXrayEngine.jvm.kt`), the tun
inbound is injected and the config sanitized (`XrayConfigSanitizer`), then handed to the controller.

## Build & run

```bash
./gradlew :desktopApp:run                 # launch the app
./gradlew :desktopApp:createDistributable # build the app image
./gradlew :desktopApp:packageDmg          # (macOS) DMG
```

### Prerequisites

- **Firebase (Firestore)** — loading configs by subscription id needs the desktop Firebase
  properties. See the firebase module's properties template + `.gitignore`’d local config
  (`core/firebase`, `JvmFirebaseConfig`). Without it the UI still launches but can't load bundles.
- **macOS VPN** — the `OnthecrowVpnService.app` must be built, signed and installed in `/Applications`,
  and the system extension activated once. **Follow [`../macosApp/README.md`](../macosApp/README.md)**
  (§5 Build & run). After that, `:desktopApp:run` → enter id → **Connect** drives the system VPN.
- **Windows VPN** — build the libXray desktop sidecar into `local-libs/libxray-desktop/` and provide
  `wintun.dll` (see `scripts/build-libxray-desktop.sh` and `DesktopVpnSupport`).

### Bundled runtime resources

`prepareDesktopVpnResources` copies the per-OS sidecar/converter (`local-libs/libxray-desktop/<os-arch>/`)
and wrapper scripts into `desktopApp/resources/<os-arch>/`, exposed at runtime via
`System.getProperty("compose.application.resources.dir")` and resolved by `DesktopVpnSupport`.

> On macOS the production packaging should also embed/install `OnthecrowVpnService.app` and run the
> one-time system-extension activation on first launch — that automation is the remaining
> productionization step (today the service app is built/installed manually per `macosApp/README.md`).
