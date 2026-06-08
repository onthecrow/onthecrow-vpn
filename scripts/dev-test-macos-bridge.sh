#!/usr/bin/env bash
#
# Dev-only end-to-end test of the native macOS NE bridge + system extension (SIP off / dev mode).
# Converts a share link to xray JSON, then runs the (already Developer-ID + NE-entitlement signed)
# bridge with a `connect` command so the installed system extension actually brings up the tunnel.
#
# Usage:  scripts/dev-test-macos-bridge.sh '<vless://... or hysteria2://... share link>'
#
# While it runs:
#   * approve the "… would like to add VPN configurations" prompt the first time,
#   * watch for {"type":"status","value":"connected"},
#   * in another terminal check egress:  curl -s https://ifconfig.me ; echo
#   * type  disconnect  + Enter to stop cleanly (or Ctrl-C).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINK="${1:-}"
[ -n "$LINK" ] || { echo "usage: $0 '<share-link>'" >&2; exit 1; }

CONVERT="$ROOT/local-libs/libxray-desktop/macos-arm64/onthecrow-convert"
# Run the bridge from the /Applications service .app (must be there so it can activate the sysext and
# so its designated requirement matches the one that activated the provider).
APP_BRIDGE="/Applications/OnthecrowVpnService.app/Contents/MacOS/onthecrow-macos-bridge"
RAW_BRIDGE="$ROOT/core/vpn/macos-bridge/build/bin/macosArm64/releaseExecutable/onthecrow-macos-bridge.kexe"
if [ -x "$APP_BRIDGE" ]; then BRIDGE="$APP_BRIDGE"; else BRIDGE="$RAW_BRIDGE"; fi
[ -x "$CONVERT" ] || { echo "converter not found: $CONVERT" >&2; exit 1; }
[ -x "$BRIDGE" ]  || { echo "bridge not found (build + sign it first): $BRIDGE" >&2; exit 1; }

echo "==> converting share link to xray JSON..."
XRAY="$(printf '%s' "$LINK" | "$CONVERT")"
[ -n "$XRAY" ] || { echo "converter produced empty output — bad link?" >&2; exit 1; }
echo "==> xray JSON ok (${#XRAY} chars)"

B64="$(printf '%s' "$XRAY" | base64 | tr -d '\n')"

echo "==> launching signed bridge with connect command"
echo "    (approve the VPN-configuration prompt; look for status=connected; 'disconnect'+Enter or Ctrl-C to stop)"
echo "------------------------------------------------------------------"
{ printf 'connect %s\n' "$B64"; cat; } | "$BRIDGE"
