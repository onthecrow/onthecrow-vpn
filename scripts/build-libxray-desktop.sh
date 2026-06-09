#!/usr/bin/env bash
#
# Builds the libXray desktop sidecar (`desktop_bin`) — a standalone Xray runner
# that creates the OS TUN device (utun on macOS, Wintun on Windows) and runs
# Xray-core. Routing / DNS / privilege handling live in the per-OS wrapper
# scripts under scripts/desktop/ — this binary is the unmodified upstream one.
#
# Output: local-libs/libxray-desktop/<os-arch>/onthecrow-xray[.exe]
#
# Targets (override with $TARGETS, space-separated): macos-arm64 macos-x64 windows-x64 windows-arm64
# Requirements: git, go.
#
set -euo pipefail

LIBXRAY_TAG="${LIBXRAY_TAG:-v26.3.27}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${ROOT_DIR}/.libxray-build"
SRC_DIR="${WORK_DIR}/libXray"
OUTPUT_BASE="${ROOT_DIR}/local-libs/libxray-desktop"
TARGETS="${TARGETS:-macos-arm64}"

require_command() {
  local cmd="$1"; local hint="$2"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Required command '${cmd}' was not found." >&2
    echo "${hint}" >&2
    exit 1
  fi
}

require_command "git" "Install Git and make sure it is available in PATH."
require_command "go" "Install Go, for example: brew install go"

mkdir -p "${WORK_DIR}"
if [ ! -d "${SRC_DIR}/.git" ]; then
  git clone https://github.com/XTLS/libXray.git "${SRC_DIR}"
fi

cd "${SRC_DIR}"
git fetch --tags
if ! git rev-parse -q --verify "refs/tags/${LIBXRAY_TAG}" >/dev/null; then
  echo "libXray tag '${LIBXRAY_TAG}' was not found." >&2
  exit 1
fi
git checkout -q "${LIBXRAY_TAG}"

# Drop our standalone share-link -> Xray JSON converter into the libXray module
# tree so it can import the internal `share` package, then build it per target.
CONVERT_SRC="${ROOT_DIR}/scripts/desktop/convert/main.go"
mkdir -p "${SRC_DIR}/onthecrow_convert"
cp "${CONVERT_SRC}" "${SRC_DIR}/onthecrow_convert/main.go"

# Overlay a patched desktop_bin main.go that prints the runXray error instead of
# silently os.Exit(1) — essential for diagnosing desktop failures.
cp "${ROOT_DIR}/scripts/desktop/sidecar-main.go" "${SRC_DIR}/desktop_bin/main.go"

build_target() {
  local target="$1"
  local goos goarch xray_name convert_name
  case "${target}" in
    macos-arm64)  goos=darwin;  goarch=arm64; xray_name=onthecrow-xray;     convert_name=onthecrow-convert ;;
    macos-x64)    goos=darwin;  goarch=amd64; xray_name=onthecrow-xray;     convert_name=onthecrow-convert ;;
    windows-x64)  goos=windows; goarch=amd64; xray_name=onthecrow-xray.exe; convert_name=onthecrow-convert.exe ;;
    windows-arm64) goos=windows; goarch=arm64; xray_name=onthecrow-xray.exe; convert_name=onthecrow-convert.exe ;;
    *) echo "Unknown target: ${target}" >&2; exit 1 ;;
  esac

  local out_dir="${OUTPUT_BASE}/${target}"
  mkdir -p "${out_dir}"

  echo "==> Building sidecar ${target} (GOOS=${goos} GOARCH=${goarch})"
  ( cd "${SRC_DIR}"
    GOOS="${goos}" GOARCH="${goarch}" CGO_ENABLED=0 \
      go build -trimpath -ldflags '-s -w' \
      -o "${out_dir}/${xray_name}" ./desktop_bin )

  echo "==> Building converter ${target}"
  ( cd "${SRC_DIR}"
    GOOS="${goos}" GOARCH="${goarch}" CGO_ENABLED=0 \
      go build -trimpath -ldflags '-s -w' \
      -o "${out_dir}/${convert_name}" ./onthecrow_convert )

  echo "==> Produced ${out_dir}/${xray_name} + ${convert_name}"
  if command -v file >/dev/null 2>&1; then
    file "${out_dir}/${xray_name}" || true
  fi

  case "${target}" in
    windows-*)
      local wintun_arch
      case "${goarch}" in amd64) wintun_arch=amd64 ;; arm64) wintun_arch=arm64 ;; esac
      echo "    NOTE: wintun.dll is NOT bundled here (separate WireGuard license/signature)."
      echo "          wintun.dll MUST match the *OS* arch (it's a kernel driver), not the emulated process."
      echo "          Download from https://www.wintun.net, take bin/${wintun_arch}/wintun.dll and place it at:"
      echo "          ${out_dir}/wintun.dll"
      echo "          The app copies it next to onthecrow-xray.exe at connect time."
      ;;
  esac
}

for t in ${TARGETS}; do
  build_target "${t}"
done

echo "Done. Desktop sidecars under: ${OUTPUT_BASE}"
