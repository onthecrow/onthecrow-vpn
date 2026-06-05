#!/usr/bin/env bash
#
# Builds LibXray.xcframework (iOS / iOS-simulator / macOS / Mac Catalyst) via
# libXray's gomobile builder, and copies it into libs/LibXray/ where the
# cinterop + Xcode "Embed Frameworks" pick it up (same layout as the Firebase
# xcframeworks already under libs/).
#
# Requirements: git, python3, go, gomobile (+ gobind in PATH), Xcode.
#
set -euo pipefail

LIBXRAY_TAG="${LIBXRAY_TAG:-v26.3.27}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${ROOT_DIR}/.libxray-build"
SRC_DIR="${WORK_DIR}/libXray"
OUTPUT_DIR="${ROOT_DIR}/libs/LibXray"

require_command() {
  local cmd="$1"; local hint="$2"
  command -v "${cmd}" >/dev/null 2>&1 || { echo "Required command '${cmd}' not found. ${hint}" >&2; exit 1; }
}

require_command git "Install Git."
require_command python3 "Install Python 3."
require_command go "Install Go (brew install go)."
require_command gomobile "go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init"

mkdir -p "${WORK_DIR}"
if [ ! -d "${SRC_DIR}/.git" ]; then
  git clone https://github.com/XTLS/libXray.git "${SRC_DIR}"
fi

cd "${SRC_DIR}"
git fetch --tags
if ! git rev-parse -q --verify "refs/tags/${LIBXRAY_TAG}" >/dev/null; then
  echo "libXray tag '${LIBXRAY_TAG}' not found." >&2
  exit 1
fi
git checkout -q "${LIBXRAY_TAG}"

echo "==> Building LibXray.xcframework via gomobile (this takes a while)"
python3 build/main.py apple gomobile

FRAMEWORK="$(find "${SRC_DIR}" -maxdepth 2 -name 'LibXray.xcframework' -type d | head -n 1)"
if [ -z "${FRAMEWORK}" ]; then
  echo "Build finished but LibXray.xcframework was not found." >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"
rm -rf "${OUTPUT_DIR}/LibXray.xcframework"
cp -R "${FRAMEWORK}" "${OUTPUT_DIR}/LibXray.xcframework"
echo "==> Copied to ${OUTPUT_DIR}/LibXray.xcframework"
echo "    Slices:"; ls "${OUTPUT_DIR}/LibXray.xcframework"
