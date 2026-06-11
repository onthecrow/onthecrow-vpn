#!/usr/bin/env bash
#
# Regenerates the app icon for iOS / macOS / Windows / Linux from a single square PNG source.
# (Android is generated separately via Android Studio's Image Asset Studio.)
#
# Source of truth: desktopApp/icons/OnthecrowVPN.png (1024×1024). Pass a different PNG as $1 to
# replace it — ideally a crisp ≥1024×1024 master.
#
# Platform note: macOS (unlike iOS) does NOT round the icon for you — it shows the artwork as-is. So
# the macOS .icns is built with a rounded-rect tile + transparent margins per Apple's Big Sur icon
# grid (tile 824 in a 1024 canvas, ~100px margins, corner radius ~185 ≈ squircle). iOS/Windows/Linux
# keep the full-bleed square (iOS auto-rounds it; Windows/Linux show it square).
#
# Outputs:
#   iosApp/.../AppIcon.appiconset/app-icon-1024.png   (1024 full-bleed, no alpha — Apple rejects transparency)
#   desktopApp/icons/OnthecrowVPN.icns                (macOS .app — ROUNDED tile + margins)
#   desktopApp/icons/OnthecrowVPN.ico                 (Windows .exe/.msi — full-bleed)
#   desktopApp/icons/OnthecrowVPN.png                 (Linux + the in-repo source of truth — full-bleed)
#
# Requires: ImageMagick (`magick`) + iconutil (ships with macOS).  Usage: scripts/generate-icons.sh [source.png]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:-$ROOT/desktopApp/icons/OnthecrowVPN.png}"
[ -f "$SRC" ] || { echo "source not found: $SRC" >&2; exit 1; }
command -v magick >/dev/null || { echo "ImageMagick (magick) not installed — brew install imagemagick" >&2; exit 1; }

WORK="$(mktemp -d)"; ICONSET="$WORK/icon.iconset"
MASTER="$WORK/master-1024.png"     # full-bleed square
MAC="$WORK/macos-1024.png"         # rounded tile + transparent margins (macOS only)
mkdir -p "$ROOT/desktopApp/icons" "$ICONSET"
magick "$SRC" -filter Lanczos -resize 1024x1024 "$MASTER"

# --- macOS tile per Apple's icon grid: 824 tile centered in 1024 canvas, corner radius ~185 ---
TILE=824; RADIUS=185
magick "$MASTER" -resize ${TILE}x${TILE} "$WORK/tile.png"
# White rounded-rect on transparent → used as an alpha mask (DstIn keeps the tile inside it).
magick -size ${TILE}x${TILE} xc:none -fill white \
  -draw "roundrectangle 0,0,$((TILE-1)),$((TILE-1)),${RADIUS},${RADIUS}" "$WORK/mask.png"
magick "$WORK/tile.png" -alpha set "$WORK/mask.png" -compose DstIn -composite "$WORK/rounded.png"
magick "$WORK/rounded.png" -background none -gravity center -extent 1024x1024 "$MAC"

# iOS — full-bleed asset-catalog icon (flatten alpha)
magick "$MASTER" -background white -alpha remove -alpha off \
  "$ROOT/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"

# macOS .icns — from the ROUNDED master (margins scale down with each size)
for s in 16 32 128 256 512; do
  magick "$MAC" -resize ${s}x${s}        "$ICONSET/icon_${s}x${s}.png"
  magick "$MAC" -resize $((s*2))x$((s*2)) "$ICONSET/icon_${s}x${s}@2x.png"
done
iconutil -c icns "$ICONSET" -o "$ROOT/desktopApp/icons/OnthecrowVPN.icns"

# Windows .ico + Linux/source .png — full-bleed
magick "$MASTER" -define icon:auto-resize=256,128,64,48,32,16 "$ROOT/desktopApp/icons/OnthecrowVPN.ico"
cp "$MASTER" "$ROOT/desktopApp/icons/OnthecrowVPN.png"

rm -rf "$WORK"
echo "icons regenerated from: $SRC"
