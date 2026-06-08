# macOS native system VPN — Xcode + packaging guide

This is the **interactive** part of the macOS NetworkExtension VPN (Phases 3–5). Everything on the
Kotlin/JVM side (Phases 1–2) is already done and verified:

- `:core:vpn:ios-tunnel` builds the `OnthecrowTunnel` **macOS** framework (reused iOS tunnel core).
- `:core:vpn:macos-bridge` builds `onthecrow-macos-bridge` — the native helper that activates the
  system extension and drives `NETunnelProviderManager` over stdio.
- `PlatformVpnController` (JVM) routes macOS to that bridge; the Compose Desktop UI is unchanged.

The only Swift is `SystemExtension/PacketTunnelProvider.swift` (~30 lines, forwards to Kotlin).

## Identifiers (keep consistent)

| Thing | Value |
|---|---|
| App bundle id (jpackage) | `com.onthecrow.onthecrowvpn` (desktopApp `packageName`) |
| System extension bundle id | `com.onthecrow.onthecrowvpn.SystemExtension` (child of the app id) |
| App Group | `group.com.onthecrow.onthecrowvpn` |
| Team | `Q468Q9633Q` |
| Provider class | `<ModuleName>.PacketTunnelProvider` |

If you change the sysext id, pass it to the bridge via the `ONTHECROW_SYSEXT_ID` env var (the JVM
controller can set it) — otherwise it defaults to `com.onthecrow.onthecrowvpn.SystemExtension`.

## Phase 3 — Build the `.systemextension` bundle (Xcode)

The most reliable way to get the bundle structure + Info.plist right is Xcode's template:

1. New Xcode project → **macOS App** (call it e.g. `OnthecrowSysextHost`, team `Q468Q9633Q`). This
   host target is only a vehicle to build/sign the extension; the real host at runtime is the JVM app.
2. Add target → **Network Extension** → **Packet Tunnel Provider** (this creates a *system extension*
   on macOS). Set its bundle id to `com.onthecrow.onthecrowvpn.SystemExtension`.
3. Replace the generated provider source with `SystemExtension/PacketTunnelProvider.swift` (here).
   Confirm Info.plist has:
   ```xml
   <key>NetworkExtension</key>
   <dict>
       <key>NEProviderClasses</key>
       <dict>
           <key>com.apple.networkextension.packet-tunnel</key>
           <string>$(PRODUCT_MODULE_NAME).PacketTunnelProvider</string>
       </dict>
   </dict>
   ```
4. Set the extension's entitlements to `SystemExtension/SystemExtension.entitlements` (here):
   `packet-tunnel-provider-systemextension` + the App Group.
5. Build the `OnthecrowTunnel` macOS framework and embed it, plus `LibXray.xcframework` (macos slice):
   ```
   ./gradlew :core:vpn:ios-tunnel:linkReleaseFrameworkMacosArm64
   # framework: core/vpn/ios-tunnel/build/bin/macosArm64/releaseFramework/OnthecrowTunnel.framework
   # libxray:   libs/LibXray/LibXray.xcframework (macos-arm64_x86_64 slice)
   ```
   Add a Run Script phase that runs that Gradle task (mirrors the iOS `embedAndSignAppleFramework`),
   link both into the extension target, `OTHER_LDFLAGS = -ObjC -lc++`.
6. Archive/build the extension (Developer ID). Output: `OnthecrowVPN.SystemExtension.systemextension`.

## Phase 4 — Package + sign the JVM app with the embedded extension

1. Build the bridge release binary:
   `./gradlew :core:vpn:macos-bridge:linkReleaseExecutableMacosArm64`
   → `core/vpn/macos-bridge/build/bin/macosArm64/releaseExecutable/onthecrow-macos-bridge.kexe`
2. Build the desktop app image: `./gradlew :desktopApp:createDistributable` (or `packageDmg`).
3. Into the produced `*.app` bundle, embed:
   - the bridge at `Contents/MacOS/onthecrow-macos-bridge` (or `Contents/Helpers/`; `resolveBridge()`
     also finds it in the Compose resources dir — simplest is to drop it in
     `desktopApp/resources/macos-arm64/onthecrow-macos-bridge`),
   - the extension at `Contents/Library/SystemExtensions/com.onthecrow.onthecrowvpn.SystemExtension.systemextension`.
4. Code-sign inside-out with **Developer ID Application** (Team `Q468Q9633Q`): framework → libxray →
   extension → bridge → app, applying `macosApp/app.entitlements` (system-extension.install + App
   Group) to the app's main executable. Then **notarize** + staple the whole `.app`/`.dmg`.

> Risk: jpackage `.app` + embedded sysext + notarization is the least-trodden step. If sysext
> activation is rejected for the JVM-spawned requester, fall back to a JNI dylib so the JVM process
> itself is the requester (its launcher carries the entitlements).

## Phase 5 — Run & verify

1. Launch the app from `/Applications`. On first connect it requests activation → approve the
   extension in **System Settings → General → Login Items & Extensions**.
2. The VPN profile appears under **System Settings → VPN**. Connect from the Compose UI → traffic
   egresses via the server; the button reflects the real `NEVPNStatus`.
3. Toggle the VPN externally in System Settings → the UI follows. Quit the app → the profile persists;
   relaunch reconciles to the real status. Failures surface via the App-Group `lastTunnelError`.
