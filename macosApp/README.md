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

The resulting **production** design is a **single, notarized `OnthecrowVPN.app`** (Developer ID): the
Compose Desktop JVM UI bundle **embeds** the system extension (`Contents/Library/SystemExtensions/`) and
a tiny native bridge (`Contents/Helpers/`); the whole bundle is deep-signed Developer ID and notarized,
so it activates the system extension with **SIP enabled** — the user just approves it once in System
Settings — and can be zipped/DMG'd and sent to anyone. The JVM UI spawns the embedded bridge over stdio;
the bridge both **activates** and **manages** the system extension, and because both live in the same
signed bundle the OS's designated-requirement pin is satisfied.

> For fast local iteration there's also a **SIP-off dev loop** that builds a standalone
> `OnthecrowVpnService.app` instead of notarizing — see §5's *Fast dev loop* subsection. The
> distributable single-bundle path below is the default.

---

## 2. Architecture (high level)

Everything lives inside **one** Developer-ID-signed, notarized `OnthecrowVPN.app`:

```
OnthecrowVPN.app  (Developer ID + notarized; CFBundleIdentifier com.onthecrow.onthecrowvpn)
└─ Contents/
   ├─ MacOS/OnthecrowVPN              ◄── Compose Desktop (JVM) UI + bundled JRE
   │     │  VpnController (commonMain) → PlatformVpnController.jvm.kt (macOS branch) → MacosNeController.kt
   │     ▼  ProcessBuilder + stdin/stdout (JSON lines)
   ├─ Helpers/onthecrow-macos-bridge  ◄── Kotlin/Native executable (core/vpn/macos-bridge)
   │     • OSSystemExtensionRequest  → activate the system extension (one-time, user approves in Settings)
   │     • NETunnelProviderManager   → create/save the VPN profile, start/stop, observe NEVPNStatus
   │     • App Group store           → read the tunnel's failure reason
   │     │  the OS launches the provider process on startVPNTunnel
   │     ▼
   ├─ Library/SystemExtensions/com.onthecrow.onthecrowvpn.SystemExtension.systemextension
   │     • PacketTunnelProvider.swift  ── ~15-line Swift principal class (required; see §4) ──┐
   │     • OnthecrowTunnelCore (Kotlin/Native, SHARED WITH iOS)  ◄────────────────────────────┘
   │          • NEPacketTunnelNetworkSettings (10.77.0.2/32, default route, DNS, MTU 1500)
   │          • finds the utun fd (getsockopt scan) → LibXraySetTunFd → LibXrayRunXrayFromJSON(config)
   │          ▼  libXray (xray-core) → hysteria2/vless → server → internet  (utun ◄ default route)
   └─ embedded.provisionprofile       ◄── Onthecrow Host DevID (authorizes the NE entitlement, SIP on)
```

Because the bridge's enclosing main bundle is `OnthecrowVPN.app`, `OSSystemExtensionRequest` is
attributed to it and the OS finds the sysext in the same bundle — the **same app** activates and manages
the provider (designated-requirement pin satisfied), with no separate install.

**One-liner:** Compose (JVM) → spawns the embedded **bridge** → it drives `NETunnelProviderManager` →
the OS runs the embedded **system extension** → our shared **`OnthecrowTunnelCore`** hands the utun fd to
**libXray (xray-core)** → traffic.

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
| Bridge resolver / helpers | `core/vpn/impl/src/jvmMain/.../DesktopVpnSupport.kt` | `resolveBridge()` finds the bridge embedded in the running bundle (`Contents/Helpers/onthecrow-macos-bridge`), with dev fallbacks. |
| **NE bridge** (KN executable) | `core/vpn/macos-bridge/` (entry `.../macosbridge/Main.kt`) | `OSSystemExtensionRequest` + `NETunnelProviderManager` driven over a line-based stdio JSON protocol; runs an `NSRunLoop`. |
| **Shared NE management** | `core/vpn/impl/src/appleMain/.../AppleTunnelManager.kt` | The `NETunnelProviderManager` create/save/start/stop/status logic shared by **iOS** (`PlatformVpnController.ios.kt`) and the macOS bridge. |
| **System-extension tunnel core** | `core/vpn/ios-tunnel/src/appleMain/.../OnthecrowTunnelCore.kt` | Reused verbatim from iOS: network settings, utun-fd scan, `LibXraySetTunFd` + `LibXrayRunXrayFromJSON`, App-Group error reporting. |
| Swift principal class | `macosApp/OnthecrowSysextHost/SystemExtension/PacketTunnelProvider.swift` | ~15 lines; forwards `start/stopTunnel` to `OnthecrowTunnelCore`. |
| Config sanitizer | `core/xray/src/commonMain/.../XrayConfigSanitizer.kt` | Injects the tun inbound, strips non-IP `sendThrough`, sets log level/error path. |
| Xcode sysext project | `macosApp/OnthecrowSysextHost/` | Builds the `.systemextension` (+ a throwaway host used during bring-up). |
| **Production packager** | `scripts/package-macos-app.sh` | Builds the sysext + bridge + jpackage app, **embeds** them into one bundle, deep-signs Developer ID, **notarizes**, staples. The one command for distribution. |
| Entitlements | `macosApp/desktop-app.entitlements` (JVM launcher/outer app: JIT trio + app group), `macosApp/macos-bridge.entitlements` (bridge: NE + system-extension.install + app group), `macosApp/OnthecrowSysextHost/SystemExtension/SystemExtension.entitlements` (sysext: NE + app group + network.client/server) | The three entitlement sets the packager applies per component. |
| Dev-loop scripts (SIP-off) | `scripts/build-macos-service-app.sh`, `scripts/dev-test-macos-bridge.sh` | Fast local iteration **without** notarization: build a standalone `OnthecrowVpnService.app` and drive the bridge directly. See §5 *Fast dev loop*. |

### Why a Swift principal class (the only Swift)
NetworkExtension resolves the provider class via `NSClassFromString` **at extension-process launch,
before any Kotlin/Native runtime initializes** — a KN subclass of `NEPacketTunnelProvider` is not in
the static `__objc_classlist` and would never be found. A *Swift* class is registered at image load,
so it's findable; it immediately forwards to `OnthecrowTunnelCore`. This is identical to the iOS
pattern. Everything else is Kotlin.

---

## 5. Build & run — production single notarized `.app`

The end product is **one** `OnthecrowVPN.app`: Developer-ID signed, notarized, runs with **SIP enabled**,
and can be sent to anyone (App Store is not an option — VPN apps require an *organization* account,
Guideline 5.4). `scripts/package-macos-app.sh` does the whole build/embed/sign/notarize/staple.

Prerequisites: **Apple Silicon Mac**, **Xcode**, a **paid Apple Developer account**, a **Developer ID
Application** certificate, and a **JDK** (for jpackage, comes with the Gradle toolchain).

### 5.1 Identifiers (keep consistent everywhere)

| Thing | Value |
|---|---|
| App bundle id | `com.onthecrow.onthecrowvpn` (pinned in `desktopApp/build.gradle.kts` → `macOS { bundleID }`) |
| System extension bundle id | `com.onthecrow.onthecrowvpn.SystemExtension` (must be a **child** of the app id) |
| App Group | `group.com.onthecrow.onthecrowvpn` |
| Team | `Q468Q9633Q` |

### 5.2 Build libXray + desktop sidecar

```bash
scripts/build-libxray-apple.sh                 # → libs/LibXray/LibXray.xcframework (macos slice; for the sysext + bridge)
TARGETS=macos-arm64 scripts/build-libxray-desktop.sh   # → local-libs/libxray-desktop/macos-arm64/onthecrow-xray (bundled by jpackage)
```

### 5.3 Apple Developer portal + notary credentials (one-time)

1. **Certificate**: Xcode → Settings → Accounts → *Manage Certificates* → **+** → **Developer ID
   Application** (lands in the login keychain).
2. **App Group**: create `group.com.onthecrow.onthecrowvpn`.
3. **App IDs** (Identifiers):
   - `com.onthecrow.onthecrowvpn` — enable **App Groups**, **Network Extensions**, **System Extension**
     (the app/bridge both manages the provider *and* activates the sysext).
   - `com.onthecrow.onthecrowvpn.SystemExtension` — enable **Network Extensions** + **App Groups**.
   > Xcode's *automatic* signing **cannot** provision the `*-systemextension` NE entitlement — hence the
   > manual Developer ID profiles below.
4. **Provisioning profiles** (Profiles → **+** → Distribution → **Developer ID**), download &
   double-click each to install (the packager auto-discovers them by name):
   - `Onthecrow Host DevID` → App ID `com.onthecrow.onthecrowvpn` (Network Extensions + System Extension + App Groups).
   - `Onthecrow Sysext DevID` → App ID `com.onthecrow.onthecrowvpn.SystemExtension`.
5. **notarytool credentials** (one-time, stored in the keychain):
   ```bash
   xcrun notarytool store-credentials onthecrow \
     --apple-id you@example.com --team-id Q468Q9633Q --password <app-specific-password>
   ```
   (Create the app-specific password at appleid.apple.com.)

### 5.4 Build the system extension target (Xcode, one-time setup)

Open `macosApp/OnthecrowSysextHost`. The **SystemExtension** target is configured with:
- **Info.plist** → `NetworkExtension` → `NEProviderClasses` → `com.apple.networkextension.packet-tunnel`
  = `$(PRODUCT_MODULE_NAME).PacketTunnelProvider`.
- **Entitlements** (`SystemExtension/SystemExtension.entitlements`):
  `networkextension = [packet-tunnel-provider-systemextension]`, the app group, and
  **`com.apple.security.network.client` + `network.server`** (App Sandbox is on — without `network.client`
  xray *starts* but can't open the outbound socket → "connected, no traffic").
- Linked (Do Not Embed — both **static**): `OnthecrowTunnel.framework`
  (`./gradlew :core:vpn:ios-tunnel:linkReleaseFrameworkMacosArm64`) + `libs/LibXray/LibXray.xcframework`.
- `OTHER_LDFLAGS = -ObjC -lc++`, `ENABLE_USER_SCRIPT_SANDBOXING = NO`, manual **Developer ID** signing
  with `Onthecrow Sysext DevID`.

The packager builds this target for you via `xcodebuild -scheme SystemExtension`; you don't need to
press ⌘B yourself once the target is set up.

### 5.5 Build + sign + notarize — one command

```bash
ONTHECROW_NOTARY_PROFILE=onthecrow scripts/package-macos-app.sh
```
This: builds the `.systemextension` (xcodebuild) + the KN bridge + the jpackage app, **embeds** the
sysext into `Contents/Library/SystemExtensions/` and the bridge into `Contents/Helpers/`, embeds the
provisioning profiles, **deep-signs** every component inside-out (Developer ID + hardened runtime), then
**notarizes** and **staples**. Result: `desktopApp/build/compose/binaries/main/app/OnthecrowVPN.app`.

Useful flags (env):
- `SKIP_NOTARIZE=1` — sign only (fast; produces a signed-but-not-notarized app for local signature checks).
- `MAKE_DMG=1` — also build, notarize and staple a DMG around the app.
- `ONTHECROW_SIGN_IDENTITY` / `ONTHECROW_HOST_PROFILE` / `ONTHECROW_SYSEXT_PROFILE` / `ONTHECROW_SYSEXT_SRC`
  — overrides if auto-discovery doesn't fit your setup.

### 5.6 First run (on any Mac, **SIP enabled**)

Launch `OnthecrowVPN.app` → enter your subscription id → pick a config → **Connect**. The first time,
macOS asks to approve the extension in **System Settings → General → Login Items & Extensions** —
approve once. The tunnel comes up; the profile appears under **System Settings → VPN**, and the button
reflects the real `NEVPNStatus`. No SIP change, no Terminal.

### 5.7 Distribution

Ship the stapled `OnthecrowVPN.app` (zip it, or `MAKE_DMG=1` for a DMG). Because it's notarized +
stapled, Gatekeeper accepts it offline on other Macs. Verify before shipping:
```bash
spctl -a -vvv -t exec OnthecrowVPN.app        # → accepted, source=Notarized Developer ID
xcrun stapler validate OnthecrowVPN.app       # → validated
codesign --verify --deep --strict --verbose=2 OnthecrowVPN.app
```

### 5.8 Fast dev loop (optional, **SIP off**)

For quick iteration without notarizing the whole bundle each time, use the standalone service app +
direct bridge driver (this is the *only* path that needs SIP changes):
```bash
csrutil disable                  # from Recovery, once
systemextensionsctl developer on # allows an un-notarized, Developer-ID-signed sysext to run
./gradlew :core:vpn:macos-bridge:linkReleaseExecutableMacosArm64
SYSEXT_APP=$(find ~/Library/Developer/Xcode/DerivedData -path "*Build/Products/*/OnthecrowSysextHost.app" | head -1)
ONTHECROW_SYSEXT_SRC="$SYSEXT_APP/Contents/Library/SystemExtensions" scripts/build-macos-service-app.sh
cp -R build/macos/OnthecrowVpnService.app /Applications/
{ printf 'activate\n'; cat; } | /Applications/OnthecrowVpnService.app/Contents/MacOS/onthecrow-macos-bridge  # approve; Ctrl-C
scripts/dev-test-macos-bridge.sh 'vless://…'   # drive a tunnel without the UI
```
`DesktopVpnSupport.resolveBridge()` falls back to this dev service app when not running from the packaged
bundle, so `./gradlew :desktopApp:run` also works in this mode.

---

## 6. Debugging appendix

Everything we leaned on during bring-up, in one place.

**System-extension state**
```bash
systemextensionsctl list | grep onthecrow          # [activated enabled] = good
systemextensionsctl developer on                   # ONLY for the §5.8 dev loop (needs SIP off)
# stale "[terminated waiting to uninstall on reboot]" piles up across rebuilds → reboot to clear
```

**Notarization / Gatekeeper (production bundle)**
```bash
APP=desktopApp/build/compose/binaries/main/app/OnthecrowVPN.app
spctl -a -vvv -t exec "$APP"          # accepted, source=Notarized Developer ID
xcrun stapler validate "$APP"         # The ticket has been validated
codesign --verify --deep --strict --verbose=2 "$APP"
# why notarization failed, if it did:  xcrun notarytool log <submission-id> --keychain-profile onthecrow
```

**Code signature / entitlements / provisioning**
```bash
APP=desktopApp/build/compose/binaries/main/app/OnthecrowVPN.app
codesign -dv --entitlements - "$APP/Contents/Helpers/onthecrow-macos-bridge"   # NE + system-extension.install + app-group
codesign -dv --entitlements - "$APP/Contents/Library/SystemExtensions/"*.systemextension
codesign --verify --verbose=2 "$APP"   # "satisfies its Designated Requirement"
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
