# macOS native system VPN (NetworkExtension)

A real macOS VPN that **registers in System Settings → VPN**, runs our vless/hysteria2 xray tunnel,
and is driven from the **Compose Desktop (JVM) UI** — while reusing the *exact* Kotlin/Native tunnel
core that powers iOS.

This document covers the **why**, the **architecture**, the full **build & signing** procedure, and a
**debugging** appendix that collects everything used while bringing this up.

> The companion JVM/UI side is documented in [`../desktopApp/README.md`](../desktopApp/README.md).

---

## 1. Motivation & constraints

The goal was a *system-registered* macOS VPN (visible under Settings → VPN, on-demand, managed by the
OS) using our existing xray stack, **without** writing a second UI in SwiftUI. That goal collides with
several hard macOS facts we discovered the hard way:

| Constraint | Consequence |
|---|---|
| **Compose can't run as a native macOS app** (only JVM Desktop, or iOS via UIKit). | The UI stays **JVM/Compose Desktop**. |
| A system VPN **requires NetworkExtension**, which is native Obj-C/Swift API. | We need a **native** component the JVM can't be. |
| `com.apple.developer.networking.networkextension` is a **restricted entitlement**: AMFI rejects a bare signed binary (`-413 "No matching profile found"`). Provisioning profiles can only live **inside a `.app` bundle**. | The NE code must run from a **provisioned `.app`**, not a loose executable. |
| The system **pins a provider to the activating app's code-signing "designated requirement"** (`Cannot create agent … missing designated requirement`). | The **same `.app`** must both **activate** and **manage** the system extension. |
| iOS-app-on-Mac (Designed for iPad / Mac Catalyst) **cannot run a packet-tunnel provider**. | Not an option. |

The resulting design (**"service .app" model**): the JVM Compose app stays the UI and spawns a tiny,
**provisioned** native helper app — `OnthecrowVpnService.app` — which both installs and drives the
system extension. The JVM app itself needs **no entitlements and no signing**; all the privileged
work is isolated in the service app (a crash in the tunnel can't take down the UI).

---

## 2. Architecture (high level)

```
┌──────────────────────────┐   the UI process is unsigned/dev — it only launches a child process
│  Compose Desktop (JVM)    │   and talks to it over stdio (JSON lines).
│  desktopApp + composeApp  │
└────────────┬─────────────┘
             │  VpnController (commonMain)
             ▼
   PlatformVpnController.jvm.kt   ── macOS branch ──►  MacosNeController.kt
             │                                              │ ProcessBuilder + stdin/stdout (JSON)
             ▼                                              ▼
   /Applications/OnthecrowVpnService.app                (provisioned: Developer ID + embedded profile)
     Contents/MacOS/onthecrow-macos-bridge   ◄── Kotlin/Native executable (core/vpn/macos-bridge)
        • OSSystemExtensionRequest  → activate the system extension (one-time)
        • NETunnelProviderManager   → create/save the VPN profile, start/stop, observe NEVPNStatus
        • App Group store           → read the tunnel's failure reason
             │  the OS launches the provider process on startVPNTunnel
             ▼
     Contents/Library/SystemExtensions/com.onthecrow.onthecrowvpn.SystemExtension.systemextension
        • PacketTunnelProvider.swift  ── ~15-line Swift principal class (required; see §4) ──┐
        • OnthecrowTunnelCore (Kotlin/Native, SHARED WITH iOS)  ◄─────────────────────────────┘
             • NEPacketTunnelNetworkSettings (10.77.0.2/32, default route, DNS, MTU 1500)
             • finds the utun fd (getsockopt scan) → LibXraySetTunFd
             • LibXrayRunXrayFromJSON(config)
             ▼
        libXray (xray-core, gomobile) → hysteria2/vless transport → server → internet
             ▲
        utun5 (NEVirtualInterface) ── packets routed here by the OS (default route)
```

**One-liner:** Compose (JVM) → spawns the **service .app** → its Kotlin/Native **bridge** drives
`NETunnelProviderManager` → the OS runs the **system extension** → our shared **`OnthecrowTunnelCore`**
hands the utun fd to **libXray (xray-core)** → traffic.

---

## 3. Technologies

- **Kotlin Multiplatform** + **Compose Multiplatform (JVM Desktop)** — UI and shared logic.
- **Kotlin/Native (`macosArm64`)** — the system-extension tunnel logic and the NE bridge, compiled
  natively and reused from iOS via a shared **`appleMain`** source set.
- **NetworkExtension** — `NEPacketTunnelProvider` packaged as a **system extension**,
  `NETunnelProviderManager` (profile management), `OSSystemExtensionRequest` (install/activate).
- **App Sandbox + entitlements** — `packet-tunnel-provider-systemextension`, `network.client/server`,
  App Groups, `system-extension.install`.
- **libXray** (XTLS/libXray, gomobile build of **xray-core**) — the actual vless/hysteria2 engine.
- **utun** + **App Groups** + **Developer ID** code signing.

---

## 4. Key components (with file paths)

| Component | Path | Role |
|---|---|---|
| JVM macOS controller | `core/vpn/impl/src/jvmMain/.../MacosNeController.kt` | Spawns the service-app bridge, streams commands (`connect`/`disconnect`) and parses JSON status back into `ConnectionStatus`. |
| JVM platform controller | `core/vpn/impl/src/jvmMain/.../PlatformVpnController.jvm.kt` | Routes macOS → `MacosNeController` (Windows keeps the osascript/Wintun sidecar). |
| Bridge resolver / helpers | `core/vpn/impl/src/jvmMain/.../DesktopVpnSupport.kt` | `resolveBridge()` points at `/Applications/OnthecrowVpnService.app/.../onthecrow-macos-bridge`. |
| **NE bridge** (KN executable) | `core/vpn/macos-bridge/` (entry `.../macosbridge/Main.kt`) | `OSSystemExtensionRequest` + `NETunnelProviderManager` driven over a line-based stdio JSON protocol; runs an `NSRunLoop`. |
| **Shared NE management** | `core/vpn/impl/src/appleMain/.../AppleTunnelManager.kt` | The `NETunnelProviderManager` create/save/start/stop/status logic shared by **iOS** (`PlatformVpnController.ios.kt`) and the macOS bridge. |
| **System-extension tunnel core** | `core/vpn/ios-tunnel/src/appleMain/.../OnthecrowTunnelCore.kt` | Reused verbatim from iOS: network settings, utun-fd scan, `LibXraySetTunFd` + `LibXrayRunXrayFromJSON`, App-Group error reporting. |
| Swift principal class | `macosApp/OnthecrowSysextHost/SystemExtension/PacketTunnelProvider.swift` | ~15 lines; forwards `start/stopTunnel` to `OnthecrowTunnelCore`. |
| Config sanitizer | `core/xray/src/commonMain/.../XrayConfigSanitizer.kt` | Injects the tun inbound, strips non-IP `sendThrough`, sets log level/error path. |
| Xcode sysext project | `macosApp/OnthecrowSysextHost/` | Builds the `.systemextension` (+ a throwaway host used during bring-up). |
| Service-app builder | `scripts/build-macos-service-app.sh` | Assembles `OnthecrowVpnService.app` (embeds the bridge + sysext + provisioning profile) and code-signs it. |
| Dev test harness | `scripts/dev-test-macos-bridge.sh` | Converts a share link and drives the signed bridge directly (no UI). |
| Entitlements (reference) | `macosApp/SystemExtension.entitlements`, `macosApp/app.entitlements`, `macosApp/macos-bridge.entitlements` | Canonical entitlement sets for the sysext / host app / bridge. |

### Why a Swift principal class (the only Swift)
NetworkExtension resolves the provider class via `NSClassFromString` **at extension-process launch,
before any Kotlin/Native runtime initializes** — a KN subclass of `NEPacketTunnelProvider` is not in
the static `__objc_classlist` and would never be found. A *Swift* class is registered at image load,
so it's findable; it immediately forwards to `OnthecrowTunnelCore`. This is identical to the iOS
pattern. Everything else is Kotlin.

---

## 5. Build & run (from scratch)

Prerequisites: **Apple Silicon Mac**, **Xcode**, a **paid Apple Developer account**, and a
**Developer ID Application** certificate.

### 5.1 Identifiers (keep consistent everywhere)

| Thing | Value |
|---|---|
| App / service bundle id | `com.onthecrow.onthecrowvpn` |
| System extension bundle id | `com.onthecrow.onthecrowvpn.SystemExtension` (must be a **child** of the app id) |
| App Group | `group.com.onthecrow.onthecrowvpn` |
| Team | `Q468Q9633Q` |

### 5.2 Build libXray (xcframework)

```bash
scripts/build-libxray-apple.sh        # → libs/LibXray/LibXray.xcframework (incl. macos-arm64_x86_64)
```

### 5.3 Apple Developer portal (one-time)

1. **Certificate**: Xcode → Settings → Accounts → *Manage Certificates* → **+** → **Developer ID
   Application**.
2. **App Group**: create `group.com.onthecrow.onthecrowvpn`.
3. **App IDs** (Identifiers):
   - `com.onthecrow.onthecrowvpn` — enable **App Groups** (assign the group).
   - `com.onthecrow.onthecrowvpn.SystemExtension` — enable **Network Extensions** + **App Groups**.
   > Xcode's *automatic* signing **cannot** provision the `*-systemextension` NE entitlement — this is
   > why we use manual Developer ID profiles below.
4. **Provisioning profiles** (Profiles → **+** → Distribution → **Developer ID**), download &
   double-click each to install:
   - `Onthecrow Host DevID` → App ID `com.onthecrow.onthecrowvpn`.
   - `Onthecrow Sysext DevID` → App ID `com.onthecrow.onthecrowvpn.SystemExtension`.

### 5.4 Build the system extension (Xcode)

Open `macosApp/OnthecrowSysextHost`. The **SystemExtension** target is configured with:

- **Info.plist** → `NetworkExtension` → `NEProviderClasses` → `com.apple.networkextension.packet-tunnel`
  = `$(PRODUCT_MODULE_NAME).PacketTunnelProvider`.
- **Entitlements** (`SystemExtension/SystemExtension.entitlements`):
  `com.apple.developer.networking.networkextension = [packet-tunnel-provider-systemextension]`,
  `com.apple.security.application-groups = [group.com.onthecrow.onthecrowvpn]`,
  **`com.apple.security.network.client` + `network.server`** (App Sandbox is on — without `network.client`
  xray *starts* but can't open the outbound socket → "connected, no traffic").
- Linked (Do Not Embed — both are **static**): `OnthecrowTunnel.framework` (built by
  `./gradlew :core:vpn:ios-tunnel:linkDebugFrameworkMacosArm64`) + `libs/LibXray/LibXray.xcframework`.
- `OTHER_LDFLAGS = -ObjC -lc++`, `ENABLE_USER_SCRIPT_SANDBOXING = NO`, a Run Script that rebuilds the
  KN framework.

Set both the host and the extension to **manual signing** → **Developer ID Application** +
the matching profiles, then **Product → Build** (⌘B). The signed `.systemextension` ends up in
`~/Library/Developer/Xcode/DerivedData/.../Build/Products/Debug/OnthecrowSysextHost.app`.

### 5.5 Build the NE bridge (Kotlin/Native)

```bash
./gradlew :core:vpn:macos-bridge:linkReleaseExecutableMacosArm64
```

### 5.6 Assemble + sign the service app

```bash
# auto-finds the "Onthecrow Host DevID" profile and the just-built .systemextension
SYSEXT_APP=$(find ~/Library/Developer/Xcode/DerivedData -path "*Build/Products/*/OnthecrowSysextHost.app" | head -1)
ONTHECROW_SYSEXT_SRC="$SYSEXT_APP/Contents/Library/SystemExtensions" scripts/build-macos-service-app.sh
```
This produces `build/macos/OnthecrowVpnService.app` (faceless `LSUIElement`, bundle id
`com.onthecrow.onthecrowvpn`) that **embeds** the bridge + the sysext + the provisioning profile and
is signed Developer ID. (Override the signing identity via `ONTHECROW_SIGN_IDENTITY` if needed.)

### 5.7 Install + activate (one-time)

```bash
cp -R build/macos/OnthecrowVpnService.app /Applications/        # activation requires /Applications
{ printf 'activate\n'; cat; } | /Applications/OnthecrowVpnService.app/Contents/MacOS/onthecrow-macos-bridge
# → approve in System Settings → General → Login Items & Extensions; wait for [activated enabled]; Ctrl-C
```

### 5.8 Run the UI

```bash
./gradlew :desktopApp:run     # enter your subscription id → pick a config → Connect
```
The button reflects the real `NEVPNStatus`; the profile appears under **System Settings → VPN**.

### 5.9 Local dev signing — two options

- **Developer ID + notarization** (no SIP change): the proper path; notarize the service app for
  distribution (`xcrun notarytool`).
- **SIP off + developer mode** (fast local iteration): `csrutil disable` from Recovery, then
  `systemextensionsctl developer on` — lets an unnotarized, Apple-Development-signed extension run.

### 5.10 Distribution (outline)

Notarize `OnthecrowVpnService.app` (Developer ID + `notarytool` + staple), bundle it into the desktop
distributable, and have the app install it to `/Applications` + run the one-time activation on first
launch. (Automating §5.5–5.7 in the desktop packaging is the remaining productionization step.)

---

## 6. Debugging appendix

Everything we leaned on during bring-up, in one place.

**System-extension state**
```bash
systemextensionsctl list | grep onthecrow          # [activated enabled] = good
systemextensionsctl developer on                   # dev mode (needs SIP off)
# stale "[terminated waiting to uninstall on reboot]" piles up across rebuilds → reboot to clear
```

**Code signature / entitlements / provisioning**
```bash
codesign -dv --entitlements - /Applications/OnthecrowVpnService.app
codesign --verify --verbose=2 /Applications/OnthecrowVpnService.app   # "satisfies its Designated Requirement"
security find-identity -v -p codesigning | grep "Developer ID Application"
# decode a profile:
security cms -D -i some.provisionprofile | plutil -convert xml1 -o - -
```

**Why won't the provider launch / start?** (the most useful log)
```bash
# what NESM/the provider did (agent creation, start, errors), right after a connect attempt:
log show --last 2m --predicate 'process == "com.onthecrow.onthecrowvpn.SystemExtension" OR process == "nesessionmanager"' \
  --info --debug | grep -iE "onthecrow|start|xray|hysteria|error|fail|deny|agent|designated|plugin|signature"
```
Signposts seen during bring-up and what they meant:
- `amfid … -413 "No matching profile found"` → bare binary with NE entitlement; must be a provisioned `.app`.
- `Cannot create agent … missing designated requirement` → the activating app ≠ the managing app.
- `Xray core failed to start: … unable to send through: <remark>` → bad `sendThrough` (sanitizer strips it).
- provider reaches **connected** but no data → App Sandbox blocked the outbound socket → add `network.client`.
- `Plugin was disabled` right after Starting → usually stale/duplicate sysext versions → reboot.

**Drive the tunnel without the UI** (signed service app must be installed):
```bash
scripts/dev-test-macos-bridge.sh 'hysteria2://…  (or vless://…)'    # converts + sends `connect`
```

**xray's own logs** — the extension is sandboxed; point `log.error` at the App Group container so it's
readable (wired through `XrayConfigSanitizer.withTunInbound(errorLogPath=…)`; currently disabled in the
bridge — re-enable to capture it):
```bash
cat "$HOME/Library/Group Containers/group.com.onthecrow.onthecrowvpn/xray-error.log"
```

**Routing / connectivity sanity** (while connected):
```bash
ifconfig utun5                                   # inet 10.77.0.2, the tunnel iface
netstat -rn -f inet | grep -iE "utun|default"    # default route should be via the tunnel
curl -s https://ifconfig.me; echo                # should print the SERVER's egress IP
```
Note: **`ping`/ICMP is not tunneled** by xray (TCP/UDP are) — a failed ping is expected, not a bug.
