# androidApp (Android VPN — VpnService)

The Android client: the shared **Compose Multiplatform UI** plus a `VpnService`
(`OnthecrowVpnService`) that runs our vless/hysteria2 xray tunnel via the **libXray** Android AAR.

Unlike iOS/macOS (NetworkExtension), Android uses the standard **`VpnService`** API: the app owns the
tun interface directly (a file descriptor) and hands it to xray-core. No system extension, no Apple
signing — just a foreground service and the VPN consent dialog.

---

## 1. Architecture (high level)

```
Compose UI (composeApp, Android)
   │  VpnController (commonMain)
   ▼
PlatformVpnController.android.kt ── startForegroundService(ACTION_CONNECT, xrayJson)
   │                                 (permission first via AndroidVpnPermissionBridge → VpnService.prepare)
   ▼
OnthecrowVpnService (android.net.VpnService)
   • Builder: addr 10.77.0.2/32, route 0.0.0.0/0, DNS 1.1.1.1, MTU 1500,
     addDisallowedApplication(self) → establish() → ParcelFileDescriptor
   • detachFd() → PlatformXrayEngine.setTunFd(fd)
   • XrayConfigSanitizer.withTunInbound(config) → PlatformXrayEngine.start(runtimeJson)
   • foreground service (specialUse) + notification; onRevoke → teardown
   ▼
PlatformXrayEngine.android ── reflection into libXray.LibXray (LibXray.aar)
   • runXrayFromJSON / setTunFd / stopXray / convertShareLinksToXrayJson / testXray
   • DialerController proxy: protectFd → AndroidVpnSocketProtector → VpnService.protect(fd)
     (xray's socket to the server bypasses the tunnel — no routing loop)
   ▼
libXray (xray-core) ── tun inbound reads/writes the fd ── hysteria2/vless → server → internet
```

**One-liner:** Compose → `PlatformVpnController.android` → `OnthecrowVpnService` builds the tun fd →
`PlatformXrayEngine` hands it to **libXray (xray-core)** via reflection → traffic, with the server
socket `protect()`-ed so it bypasses the tunnel.

---

## 2. Key components

| Component | Path | Role |
|---|---|---|
| Android VPN controller | `core/vpn/impl/src/androidMain/.../PlatformVpnController.android.kt` | Starts/stops `OnthecrowVpnService` via intents; status from `AndroidVpnRuntime`. |
| **VpnService** | `core/vpn/impl/src/androidMain/.../OnthecrowVpnService.kt` | Builds the tun interface, drives xray, foreground service + notification, **network-change resilience** (refresh xray when the underlying network switches), `onRevoke`. |
| Permission bridge | `…/AndroidVpnPermissionBridge.kt` (+ `PlatformVpnPermissionRequester.android.kt`) | `VpnService.prepare()` → activity result via a launcher bound in `MainActivity`. |
| Status holder / env | `…/AndroidVpnRuntime.kt`, `…/AndroidVpnEnvironment.kt` | `StateFlow<ConnectionStatus>`; app `Context` holder. |
| **xray engine** | `core/xray/src/androidMain/.../PlatformXrayEngine.android.kt` | Reflection bridge into `libXray.LibXray` (AAR); `protectFd` via a `DialerController` `Proxy`. |
| Socket protector | `…/AndroidVpnSocketProtector.kt` | Routes libXray's `protectFd` to `VpnService.protect()`. |
| Activity | `androidApp/src/main/.../MainActivity.kt` | VPN-permission launcher, `POST_NOTIFICATIONS` request, battery-optimization exemption. |
| Manifest | `androidApp/src/main/AndroidManifest.xml` | Permissions + the `BIND_VPN_SERVICE` foreground service (`specialUse`/`vpn`). |
| Config sanitizer | `core/xray/src/commonMain/.../XrayConfigSanitizer.kt` | Injects the tun inbound, strips non-IP `sendThrough`, sets log level. |

### Design notes (learned the hard way)
- **`addDisallowedApplication(self)`** excludes our own process from the tunnel, so this process's
  default network is the *real physical* network — letting us observe Wi-Fi↔LTE switches via
  `registerDefaultNetworkCallback`. We **deliberately do not call `setUnderlyingNetworks`** (Android
  auto-follows the default network; pinning it broke fallback).
- **Network resilience**: on an underlying-network change, the service rebuilds the tun + restarts
  xray (`runConnect(restart=true)`) while keeping the session/`Connected` status (no flicker, no
  re-prompt). Refresh failures are non-fatal.
- **Doze**: while the screen is off, Doze throttles a non-exempt app's network and kills the tunnel —
  `MainActivity` requests the **battery-optimization exemption** to keep it alive.
- **Foreground service + `POST_NOTIFICATIONS`**: required so the VPN notification shows on Android 13+.

---

## 3. Technologies

- **Kotlin Multiplatform** + **Compose Multiplatform (Android)**; Koin DI; `androidx.lifecycle`.
- **`android.net.VpnService`** (tun fd, `protect()`, `addDisallowedApplication`, `onRevoke`),
  **`ConnectivityManager`** default-network callbacks, foreground service, `ParcelFileDescriptor`.
- **libXray** (XTLS/libXray, gomobile build of **xray-core**) as an **`.aar`**, called via Java
  reflection (`runXrayFromJSON`, `setTunFd`, `stopXray`, `convertShareLinksToXrayJson`, `testXray`,
  `registerDialerController`).

---

## 4. Build & run

### 4.1 libXray AAR (required for real validate/connect)
```bash
scripts/build-libxray-android.sh      # → local-libs/libxray/LibXray.aar (Gradle picks it up automatically)
```
Requires Android SDK/NDK, Go, gomobile, Python 3. Without the AAR the app still compiles, but
validate/connect return a clear `libXray is not installed` error.

### 4.2 Firebase
Place `google-services.json` in `androidApp/` (Firestore loads configs by subscription id).

### 4.3 Build / install
```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug      # onto a connected device/emulator
```

### 4.4 On device
On connect the system shows the **VPN consent** dialog (`VpnService.prepare`). On Android 13+ grant
the **notification** permission (for the foreground-service notification), and accept the
**battery-optimization exemption** prompt (keeps the tunnel alive through Doze). The key icon appears
in the status bar while connected.

### 4.5 Distribution
Standard Android release: `:androidApp:assembleRelease` / `bundleRelease` (signed APK/AAB). The libXray
AAR is bundled into the app. (GoLog is silenced in release builds.)

---

## 5. Debugging

- **Service logs** (debug builds only — gated by `isDebuggable`, tag `OnthecrowVpn`):
  ```bash
  adb logcat -s OnthecrowVpn:*
  ```
  Shows connect/refresh lifecycle, underlying-network transitions (`underlying changed: … -> …`),
  restart begin/success/failure, and teardown reasons.
- **Snapshot a run** (e.g. after a Doze/network test):
  ```bash
  adb logcat -c                 # clear
  # … reproduce (screen off 5 min / toggle Wi-Fi↔LTE) …
  adb logcat -d > /tmp/run.log  # dump
  ```
- **Network-change tests**: connect on Wi-Fi (verify egress = server), turn Wi-Fi off → traffic should
  resume over LTE once it's up (status stays Connected); toggle back; airplane on→off.
- **Doze test**: connect, screen off ~5 min, wake → traffic should still work (needs the battery
  exemption granted).
- **Competing VPN apps**: only one VpnService can hold the tunnel — force-stop other VPN clients
  (Happ/V2Box/etc.) when testing, or `onRevoke` will tear ours down.
- **xray-level errors** surface through the engine's `XrayRunResult.Failure(message)` → snackbar; bump
  the sanitizer log level (`withTunInbound(logLevel = "info"/"debug")`) for more detail in logcat.
