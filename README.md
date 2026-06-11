# OnthecrowVPN

Kotlin Multiplatform / Compose Multiplatform VPN proof of concept.

**Android**, **iOS**, and **macOS** have real, working VPN implementations sharing the same Compose UI,
domain logic and persistence; Windows uses an elevated sidecar; Linux reports an unsupported message.
Per-platform deep dives (architecture, build & signing, debugging):

- **Android** — `VpnService` + the libXray AAR — **[`androidApp/README.md`](androidApp/README.md)**.
- **iOS** — a NetworkExtension **packet-tunnel app extension** reusing a shared Kotlin/Native tunnel
  core — **[`iosApp/README.md`](iosApp/README.md)**.
- **macOS** — a native NetworkExtension **system VPN** (registered in System Settings) driven from the
  Compose Desktop UI, reusing the *same* tunnel core as iOS, shipped as a **single Developer-ID-signed,
  notarized `.app`** (system extension + bridge embedded; runs with SIP on) —
  **[`macosApp/README.md`](macosApp/README.md)** + **[`desktopApp/README.md`](desktopApp/README.md)**
  (the JVM/UI side and per-OS VPN wiring).

The iOS and macOS tunnel core (`OnthecrowTunnelCore`) and NE management (`AppleTunnelManager`) are a
single Kotlin/Native codebase shared via an `appleMain` source set.

## Architecture

- `composeApp`: shared Compose app entrypoint, Koin initialization, Navigation3 host.
- `androidApp`, `iosApp`, `desktopApp`: thin platform launchers.
- `macosApp`: the macOS NetworkExtension system extension (Xcode) + the service-app entitlements/scripts — see [`macosApp/README.md`](macosApp/README.md).
- `core:*`: coroutines, datastore, navigation, UI theme, Xray bridge (`core:xray`), VPN API/impl (`core:vpn:api`, `core:vpn:impl`), the iOS/macOS tunnel core (`core:vpn:ios-tunnel`), and the macOS NE bridge executable (`core:vpn:macos-bridge`).
- `feature:connection:*`: config validation/persistence use cases and the connection UI.

Config validity is intentionally delegated to Xray/libXray. The app trims and checks for empty input, then calls `ConvertShareLinksToXrayJson` and `TestXray`; successful raw config is stored in app-private DataStore and revalidated on the next launch.

## libXray for Android

The repository does not commit a built AAR. Build it locally when you want real Android validation/connect behavior:

```bash
scripts/build-libxray-android.sh
```

The script pins `LIBXRAY_TAG` by default and copies the resulting artifact to `local-libs/libxray/LibXray.aar`, which Gradle picks up automatically. Requirements are the Android SDK/NDK, Go, gomobile, Python 3, and the toolchain required by the pinned `XTLS/libXray` build.

Without the AAR, Android still compiles, but validation returns a clear `libXray is not installed` error.

## Useful Commands

```bash
./gradlew :feature:connection:logic-impl:jvmTest :feature:connection:ui-impl:jvmTest
./gradlew :androidApp:compileDebugKotlin :composeApp:compileKotlinIosSimulatorArm64 :desktopApp:compileKotlin
./gradlew :desktopApp:run
```

For iOS, build the `iosApp` scheme from Xcode or run `xcodebuild` against an available iOS simulator.
