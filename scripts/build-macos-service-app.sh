#!/usr/bin/env bash
#
# Builds + signs OnthecrowVpnService.app — a faceless (LSUIElement) .app wrapping the native NE
# bridge, signed Developer ID with an embedded provisioning profile so the restricted
# NetworkExtension entitlement is honored (a bare binary can't carry it — AMFI -413).
#
# Prereq: an "Onthecrow Host DevID" Developer ID provisioning profile for App ID
#   com.onthecrow.onthecrowvpn that includes the Network Extensions capability (download + double
#   click to install it after enabling Network Extensions on that App ID in the portal).
#
# Usage:  scripts/build-macos-service-app.sh [path/to/profile.provisionprofile]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE="$ROOT/core/vpn/macos-bridge/build/bin/macosArm64/releaseExecutable/onthecrow-macos-bridge.kexe"
ENTITLEMENTS="$ROOT/macosApp/macos-bridge.entitlements"
# codesign matches this as a prefix of your cert's common name; override via env if you have several.
IDENTITY="${ONTHECROW_SIGN_IDENTITY:-Developer ID Application}"
APP="$ROOT/build/macos/OnthecrowVpnService.app"
BUNDLE_ID="com.onthecrow.onthecrowvpn"

[ -x "$BRIDGE" ] || { echo "bridge not built: $BRIDGE" >&2; exit 1; }

# Locate the profile: explicit arg, else the newest installed/downloaded one named
# "Onthecrow Host DevID" that actually contains the networkextension entitlement.
PROFILE="${1:-}"
if [ -z "$PROFILE" ]; then
  search_dirs=(
    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
    "$HOME/Library/MobileDevice/Provisioning Profiles"
    "$HOME/Downloads"
  )
  newest=0
  for d in "${search_dirs[@]}"; do
    [ -d "$d" ] || continue
    while IFS= read -r f; do
      plist="$(security cms -D -i "$f" 2>/dev/null)" || continue
      name="$(printf '%s' "$plist" | plutil -extract Name raw - 2>/dev/null || true)"
      [ "$name" = "Onthecrow Host DevID" ] || continue
      printf '%s' "$plist" | grep -q "networkextension" || continue
      mt="$(stat -f %m "$f")"
      if [ "$mt" -gt "$newest" ]; then newest="$mt"; PROFILE="$f"; fi
    done < <(find "$d" -maxdepth 1 -name '*.provisionprofile' 2>/dev/null)
  done
fi
[ -n "$PROFILE" ] && [ -f "$PROFILE" ] || {
  echo "No 'Onthecrow Host DevID' profile with Network Extensions found." >&2
  echo "Regenerate it in the portal (enable Network Extensions on App ID $BUNDLE_ID), download," >&2
  echo "double-click to install, then re-run (or pass its path as an argument)." >&2
  exit 1
}
echo "==> using profile: $PROFILE"

echo "==> assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BRIDGE" "$APP/Contents/MacOS/onthecrow-macos-bridge"
cp "$PROFILE" "$APP/Contents/embedded.provisionprofile"

# Embed the (already Xcode-signed) system extension so THIS app can both activate AND manage it —
# the system pins the provider to the activating app's designated requirement, so the same app must
# do both (otherwise: "Cannot create agent ... missing designated requirement").
SYSEXT=""
for cand in \
  "${ONTHECROW_SYSEXT_SRC:-}" \
  "/Applications/OnthecrowVpnService.app/Contents/Library/SystemExtensions" \
  "/Applications/OnthecrowSysextHost.app/Contents/Library/SystemExtensions" \
  "$APP/Contents/Library/SystemExtensions"; do
  [ -n "$cand" ] || continue
  found="$(find "$cand" -maxdepth 1 -name '*.systemextension' -type d 2>/dev/null | head -1)"
  [ -n "$found" ] && SYSEXT="$found" && break
done
[ -n "$SYSEXT" ] || { echo "system extension not found (build it in Xcode, or set ONTHECROW_SYSEXT_SRC)" >&2; exit 1; }
mkdir -p "$APP/Contents/Library/SystemExtensions"
cp -R "$SYSEXT" "$APP/Contents/Library/SystemExtensions/"
echo "==> embedded sysext: $(basename "$SYSEXT")"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>${BUNDLE_ID}</string>
    <key>CFBundleName</key><string>OnthecrowVpnService</string>
    <key>CFBundleExecutable</key><string>onthecrow-macos-bridge</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
</dict>
</plist>
PLIST

echo "==> signing (Developer ID + embedded profile + NE entitlements)"
codesign --force --options runtime --timestamp=none --sign "$IDENTITY" \
  --entitlements "$ENTITLEMENTS" "$APP/Contents/MacOS/onthecrow-macos-bridge"
codesign --force --options runtime --timestamp=none --sign "$IDENTITY" \
  --entitlements "$ENTITLEMENTS" "$APP"

echo "==> done: $APP"
echo "--- entitlements on the embedded executable ---"
codesign -d --entitlements - "$APP/Contents/MacOS/onthecrow-macos-bridge" 2>&1 \
  | grep -E "networkextension|application-groups|system-extension|packet-tunnel" || true
echo "--- profile validity (codesign verify) ---"
codesign --verify --verbose=2 "$APP" 2>&1 | tail -3 || true
