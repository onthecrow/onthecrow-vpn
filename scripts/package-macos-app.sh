#!/usr/bin/env bash
#
# Builds a SINGLE, self-contained, Developer-ID signed + notarized OnthecrowVPN.app for macOS.
#
# The one bundle embeds:
#   - the Compose Desktop JVM app + bundled JRE (jpackage output),
#   - the native NE bridge          → Contents/Helpers/onthecrow-macos-bridge,
#   - the NE system extension       → Contents/Library/SystemExtensions/…SystemExtension.systemextension,
#   - the app's provisioning profile → Contents/embedded.provisionprofile.
#
# After deep-signing + notarization the app activates the system extension with **SIP enabled** — the
# user just approves it once in System Settings. No separate service .app, no /Applications copy, no
# SIP-off / `systemextensionsctl developer on`. (For the fast SIP-off dev loop use the older
# scripts/build-macos-service-app.sh + scripts/dev-test-macos-bridge.sh instead.)
#
# Prereqs (see macosApp/README.md §5):
#   * "Developer ID Application" certificate in the login keychain (Team Q468Q9633Q).
#   * Two Developer ID provisioning profiles installed/downloaded:
#       - "Onthecrow Host DevID"   (App ID com.onthecrow.onthecrowvpn, caps: Network Extensions +
#                                   System Extension + App Groups)
#       - "Onthecrow Sysext DevID" (App ID com.onthecrow.onthecrowvpn.SystemExtension, caps: Network
#                                   Extensions + App Groups)
#   * A notarytool keychain profile:  xcrun notarytool store-credentials onthecrow \
#         --apple-id you@example.com --team-id Q468Q9633Q --password <app-specific-password>
#   * Xcode (for building the system extension) and a JDK (for jpackage).
#
# Env:
#   ONTHECROW_SIGN_IDENTITY   codesign identity prefix       (default: "Developer ID Application")
#   ONTHECROW_NOTARY_PROFILE  notarytool keychain profile     (default: "onthecrow")
#   ONTHECROW_HOST_PROFILE    path to the Host DevID profile   (default: auto-discover by name)
#   ONTHECROW_SYSEXT_PROFILE  path to the Sysext DevID profile (default: auto-discover by name)
#   ONTHECROW_SYSEXT_SRC      dir containing a prebuilt *.systemextension (default: build via xcodebuild)
#   SKIP_NOTARIZE=1           sign only (faster; produces a signed but un-notarized .app for local checks)
#   MAKE_DMG=1                also build + notarize + staple a DMG around the app
#
# Usage:  scripts/package-macos-app.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IDENTITY="${ONTHECROW_SIGN_IDENTITY:-Developer ID Application}"
NOTARY_PROFILE="${ONTHECROW_NOTARY_PROFILE:-onthecrow}"
APP="$ROOT/desktopApp/build/compose/binaries/main/app/OnthecrowVPN.app"
BRIDGE_KEXE="$ROOT/core/vpn/macos-bridge/build/bin/macosArm64/releaseExecutable/onthecrow-macos-bridge.kexe"
DERIVED="$ROOT/build/macos-derived"
XCODEPROJ="$ROOT/macosApp/OnthecrowSysextHost/OnthecrowSysextHost.xcodeproj"

ENT_APP="$ROOT/macosApp/desktop-app.entitlements"
ENT_BRIDGE="$ROOT/macosApp/macos-bridge.entitlements"
ENT_SYSEXT="$ROOT/macosApp/OnthecrowSysextHost/SystemExtension/SystemExtension.entitlements"

log() { printf '\n==> %s\n' "$*"; }

# --- locate a Developer ID provisioning profile by its "Name" -------------------------------------
find_profile() {
  local want="$1"
  local dirs=(
    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
    "$HOME/Library/MobileDevice/Provisioning Profiles"
    "$HOME/Downloads"
  )
  local newest=0 found=""
  local d f plist name mt
  for d in "${dirs[@]}"; do
    [ -d "$d" ] || continue
    while IFS= read -r f; do
      plist="$(security cms -D -i "$f" 2>/dev/null)" || continue
      name="$(printf '%s' "$plist" | plutil -extract Name raw - 2>/dev/null || true)"
      [ "$name" = "$want" ] || continue
      mt="$(stat -f %m "$f")"
      if [ "$mt" -gt "$newest" ]; then newest="$mt"; found="$f"; fi
    done < <(find "$d" -maxdepth 1 -name '*.provisionprofile' 2>/dev/null)
  done
  printf '%s' "$found"
}

# --- sign every Mach-O file under a directory (no entitlements, hardened runtime) -----------------
sign_machos_in() {
  local dir="$1"
  [ -d "$dir" ] || return 0
  local f
  while IFS= read -r f; do
    if file -b "$f" | grep -q "Mach-O"; then
      codesign --force --options runtime --timestamp --sign "$IDENTITY" "$f"
    fi
  done < <(find "$dir" -type f)
}

# =================================================================================================
log "1/7  building inputs (system extension, bridge, jpackage app)"

if [ -n "${ONTHECROW_SYSEXT_SRC:-}" ]; then
  SYSEXT="$(find "$ONTHECROW_SYSEXT_SRC" -maxdepth 1 -name '*.systemextension' -type d 2>/dev/null | head -1)"
else
  command -v xcodebuild >/dev/null || { echo "xcodebuild not found (install Xcode)" >&2; exit 1; }
  rm -rf "$DERIVED"
  xcodebuild -project "$XCODEPROJ" -scheme SystemExtension -configuration Release \
    -derivedDataPath "$DERIVED" build >/dev/null
  SYSEXT="$(find "$DERIVED/Build/Products" -name '*.systemextension' -type d 2>/dev/null | head -1)"
fi
[ -n "${SYSEXT:-}" ] && [ -d "$SYSEXT" ] || { echo "system extension not found (set ONTHECROW_SYSEXT_SRC)" >&2; exit 1; }
echo "    sysext: $SYSEXT"

./gradlew -p "$ROOT" :core:vpn:macos-bridge:linkReleaseExecutableMacosArm64 >/dev/null
[ -x "$BRIDGE_KEXE" ] || { echo "bridge not built: $BRIDGE_KEXE" >&2; exit 1; }

./gradlew -p "$ROOT" :desktopApp:createDistributable >/dev/null
[ -d "$APP" ] || { echo "jpackage app not found: $APP" >&2; exit 1; }

# --- profiles ------------------------------------------------------------------------------------
HOST_PROFILE="${ONTHECROW_HOST_PROFILE:-$(find_profile "Onthecrow Host DevID")}"
SYSEXT_PROFILE="${ONTHECROW_SYSEXT_PROFILE:-$(find_profile "Onthecrow Sysext DevID")}"
[ -f "${HOST_PROFILE:-}" ]   || { echo "missing 'Onthecrow Host DevID' profile (set ONTHECROW_HOST_PROFILE)" >&2; exit 1; }
[ -f "${SYSEXT_PROFILE:-}" ] || { echo "missing 'Onthecrow Sysext DevID' profile (set ONTHECROW_SYSEXT_PROFILE)" >&2; exit 1; }

# =================================================================================================
log "2/7  embedding sysext + bridge + profiles into the bundle"
mkdir -p "$APP/Contents/Library/SystemExtensions" "$APP/Contents/Helpers"
rm -rf "$APP/Contents/Library/SystemExtensions/"*.systemextension
cp -R "$SYSEXT" "$APP/Contents/Library/SystemExtensions/"
EMB_SYSEXT="$APP/Contents/Library/SystemExtensions/$(basename "$SYSEXT")"
cp "$BRIDGE_KEXE" "$APP/Contents/Helpers/onthecrow-macos-bridge"
cp "$HOST_PROFILE"   "$APP/Contents/embedded.provisionprofile"
cp "$SYSEXT_PROFILE" "$EMB_SYSEXT/Contents/embedded.provisionprofile"

# =================================================================================================
log "3/7  signing nested Mach-O (JRE + bundled sidecars)"
sign_machos_in "$APP/Contents/runtime"
sign_machos_in "$APP/Contents/app/resources"   # onthecrow-xray / onthecrow-convert (Mach-O)

log "4/7  signing system extension (NE entitlements + Sysext profile)"
codesign --force --options runtime --timestamp --sign "$IDENTITY" --entitlements "$ENT_SYSEXT" "$EMB_SYSEXT"

log "5/7  signing bridge + launcher"
codesign --force --options runtime --timestamp --sign "$IDENTITY" --entitlements "$ENT_BRIDGE" \
  "$APP/Contents/Helpers/onthecrow-macos-bridge"
codesign --force --options runtime --timestamp --sign "$IDENTITY" --entitlements "$ENT_APP" \
  "$APP/Contents/MacOS/OnthecrowVPN"

log "6/7  sealing the whole .app"
codesign --force --options runtime --timestamp --sign "$IDENTITY" --entitlements "$ENT_APP" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

# =================================================================================================
if [ "${SKIP_NOTARIZE:-0}" = "1" ]; then
  log "7/7  SKIP_NOTARIZE=1 — signed but NOT notarized. Gatekeeper will block on other Macs."
  echo "    $APP"
  exit 0
fi

log "7/7  notarizing"
ZIP="$ROOT/build/macos/OnthecrowVPN.zip"
mkdir -p "$ROOT/build/macos"
/usr/bin/ditto -c -k --keepParent "$APP" "$ZIP"
xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" --wait
xcrun stapler staple "$APP"
rm -f "$ZIP"

if [ "${MAKE_DMG:-0}" = "1" ]; then
  log "building + notarizing DMG"
  DMG="$ROOT/build/macos/OnthecrowVPN-1.0.0.dmg"
  rm -f "$DMG"
  /usr/bin/hdiutil create -volname "OnthecrowVPN" -srcfolder "$APP" -ov -format UDZO "$DMG"
  codesign --force --timestamp --sign "$IDENTITY" "$DMG"
  xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
  xcrun stapler staple "$DMG"
  echo "    DMG: $DMG"
fi

log "done"
echo "    app: $APP"
spctl -a -vvv -t exec "$APP" 2>&1 | sed 's/^/    /' || true
xcrun stapler validate "$APP" 2>&1 | sed 's/^/    /' || true
