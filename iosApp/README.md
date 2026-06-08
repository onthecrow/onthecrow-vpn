# iosApp (iOS VPN — NetworkExtension app extension)

The iOS client: a SwiftUI shell hosting the **shared Compose Multiplatform UI**, plus a
**NEPacketTunnelProvider packet-tunnel app extension** that runs our vless/hysteria2 xray tunnel.

> iOS is the **simpler sibling of macOS**. It reuses the *exact same* Kotlin/Native tunnel core and NE
> management code (the shared `appleMain` source set), but ships the provider as an **app extension**
> embedded in the app — so there is **no** system-extension activation, **no** separate service app,
> **no** `OSSystemExtensionRequest`, **no** Developer ID / SIP / notarization dance. Contrast with the
> macOS design in [`../macosApp/README.md`](../macosApp/README.md).

---

## 1. Architecture (high level)

```
┌───────────────────────────────┐
│ iOSApp.swift → ContentView     │  SwiftUI shell
│   → ComposeView (UIViewController)
│   → MainViewControllerKt.MainViewController()   (composeApp/iosMain/MainViewController.kt)
│   → AppInitializer + App()      │  ← the shared Compose UI / Koin / domain
└───────────────┬───────────────┘
                │  VpnController (commonMain)
                ▼
   PlatformVpnController.ios.kt  (core/vpn/impl/appleMain) ── NETunnelProviderManager + status polling
                │   creates/saves the profile (providerBundleIdentifier = <app>.PacketTunnel),
                │   startVPNTunnel, maps NEVPNStatus → ConnectionStatus
                ▼
   PacketTunnel.appex  (iosApp/PacketTunnel/, embedded in the app)
        • PacketTunnelProvider.swift  ── ~15-line Swift principal class ──┐
        • OnthecrowTunnelCore (Kotlin/Native, SHARED WITH macOS)  ◄───────┘
             • NEPacketTunnelNetworkSettings, utun fd, LibXraySetTunFd + LibXrayRunXrayFromJSON
             ▼
        libXray (xray-core) → hysteria2/vless → server → internet
```

**One-liner:** SwiftUI → Compose (KMP) → `PlatformVpnController.ios` (`NETunnelProviderManager`) → iOS
launches the **PacketTunnel app extension** → Swift principal class → shared `OnthecrowTunnelCore` →
**libXray** → utun → traffic.

---

## 2. Key components

| Component | Path | Role |
|---|---|---|
| SwiftUI shell | `iosApp/iosApp/iOSApp.swift`, `ContentView.swift` | Hosts Compose via `UIViewControllerRepresentable`. |
| Compose entry | `composeApp/src/iosMain/.../MainViewController.kt` | `ComposeUIViewController { AppInitializer.initialize(IOSPlatform()); App() }`. |
| iOS VPN controller | `core/vpn/impl/src/appleMain/.../PlatformVpnController.ios.kt` | `NETunnelProviderManager` mgmt + **status polling** of `NEVPNConnection.status` (notification callbacks proved unreliable in KN); turns a failed attempt into an informative `Error` via the App-Group store. |
| **Shared NE management** | `core/vpn/impl/src/appleMain/.../AppleTunnelManager.kt` | Create/save/start/stop + status map, **shared with the macOS bridge**. |
| Extension principal class | `iosApp/PacketTunnel/PacketTunnelProvider.swift` | ~15 lines; forwards `start/stopTunnel` to `OnthecrowTunnelCore`. |
| **Tunnel core** | `core/vpn/ios-tunnel/src/appleMain/.../OnthecrowTunnelCore.kt` | The actual tunnel: network settings, utun-fd scan, `LibXraySetTunFd` + `LibXrayRunXrayFromJSON`, App-Group error reporting. **Shared with macOS.** |
| Share-link convert/validate | `core/xray/src/appleMain/.../PlatformXrayEngine.ios.kt` | `ConvertShareLinksToXrayJson` / `TestXray` via the libXray cinterop. |
| Config sanitizer | `core/xray/src/commonMain/.../XrayConfigSanitizer.kt` | tun inbound, strip non-IP `sendThrough`, log level. |

### Why a Swift principal class (the only Swift)
NetworkExtension resolves `NSExtensionPrincipalClass` via `NSClassFromString` **at extension launch,
before the KN runtime initializes**, so a Kotlin/Native subclass of `NEPacketTunnelProvider` is never
found. A Swift class is registered at image load; it forwards everything to `OnthecrowTunnelCore`. (KN
also can't safely call variadic `NSLog`, so logging is routed through a Swift closure.)

---

## 3. Technologies

- **Kotlin Multiplatform** + **Compose Multiplatform (iOS)** via `ComposeUIViewController`.
- **Kotlin/Native (`iosArm64`/`iosSimulatorArm64`)** — tunnel core + NE management, shared with macOS
  through `appleMain`.
- **NetworkExtension** — `NEPacketTunnelProvider` **app extension** + `NETunnelProviderManager`.
- **libXray** (xray-core via gomobile) linked as an `.xcframework`; **Firebase** (Firestore) for configs.

---

## 4. Build & run

Prerequisites: **Xcode**, an Apple Developer account (a free personal team works for on-device dev),
and a **physical device** — NetworkExtension does **not** run in the iOS Simulator.

### 4.1 Identifiers / signing
- `iosApp/Configuration/Config.xcconfig`: `TEAM_ID=Q468Q9633Q`, `PRODUCT_NAME=OnthecrowVPN`.
- App bundle id `com.onthecrow.onthecrowvpn.OnthecrowVPN` (`.dev` for Debug); extension is the child
  id `…OnthecrowVPN.PacketTunnel`.
- Both the app and the extension carry (`iosApp/iosApp/iosApp.entitlements`,
  `iosApp/PacketTunnel/PacketTunnel.entitlements`):
  - `com.apple.developer.networking.networkextension = [packet-tunnel-provider]` (the iOS
    **app-extension** value — note: **no** `-systemextension` suffix, unlike macOS),
  - `com.apple.security.application-groups = [group.com.onthecrow.onthecrowvpn]`.
- **App IDs** in the portal: enable **Network Extensions** + **App Groups** on both the app id and the
  `…PacketTunnel` id; create the App Group. Unlike macOS, **automatic signing works** for the iOS NE
  app-extension entitlement.

### 4.2 libXray + Firebase
- Build the Apple xcframework: `scripts/build-libxray-apple.sh` → `libs/LibXray/LibXray.xcframework`
  (the `ios-arm64` + simulator slices). The cinterop is wired in `core/xray` / `core/vpn/ios-tunnel`.
- Place `GoogleService-Info-Debug.plist` / `GoogleService-Info-Release.plist` in
  `iosApp/Configuration/` (a build phase copies the right one to the app bundle).

### 4.3 Build phases (already configured in `iosApp.xcodeproj`)
- App target Run Script: `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` (builds the
  Compose UI framework).
- Extension target Run Script: `./gradlew :core:vpn:ios-tunnel:embedAndSignAppleFrameworkForXcode`
  (builds the `OnthecrowTunnel` framework).
- Both link `LibXray.xcframework`; `OTHER_LDFLAGS = -ObjC -lc++`; `ENABLE_USER_SCRIPT_SANDBOXING = NO`.
- `PacketTunnel/Info.plist`: `NSExtensionPointIdentifier = com.apple.networkextension.packet-tunnel`,
  `NSExtensionPrincipalClass = $(PRODUCT_MODULE_NAME).PacketTunnelProvider`.

### 4.4 Run
Open `iosApp/iosApp.xcodeproj` in Xcode, select your **device**, set the team to `Q468Q9633Q` (or your
own), and Run. On first connect iOS shows the standard *"… would like to add VPN configurations"*
prompt; after that the profile lives under **Settings → VPN & Device Management**.

Or build the framework from the CLI: `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` then
`xcodebuild`.

### 4.5 Distribution
TestFlight / App Store: the app extension is archived as part of the app (Product → Archive →
Distribute). No notarization (that's a macOS Developer-ID concept).

---

## 5. Debugging

- **Run on a physical device** — NE is unavailable in the Simulator.
- **Extension logs**: Console.app (filter by the device + `PacketTunnel` / `OnthecrowTunnel`), or
  attach Xcode to the extension. `OnthecrowTunnelCore` logs each step (`startTunnel: begin`,
  `utun fd=…`, `xray error: …`) through the Swift `NSLog` closure (DEBUG builds).
- **Failure reason in the app**: the extension writes the human-readable reason to the shared App
  Group (`NSUserDefaults(suiteName: "group.com.onthecrow.onthecrowvpn")`, key `lastTunnelError`);
  `PlatformVpnController.ios` reads it when a connect attempt drops back to disconnected and surfaces
  it in the snackbar.
- **Status desync**: status is **polled** (`POLL_INTERVAL_MS = 500`) from the live `NEVPNConnection`,
  and reconciled at launch — the button reflects the real system state (including toggles from
  Settings) within a tick.
- **`tunnelRemoteAddress`** must be an IP literal; for domain servers `OnthecrowTunnelCore` uses a
  placeholder (`192.0.2.1`) — xray still dials the real host from the config.
- Signing issues building from Android Studio: the project is fine from Xcode/CLI; AS just needs the
  Apple ID account configured in Xcode.
