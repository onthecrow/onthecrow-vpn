#!/usr/bin/env bash
set -euo pipefail

LIBXRAY_TAG="${LIBXRAY_TAG:-v26.3.27}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${ROOT_DIR}/.libxray-build"
OUTPUT_DIR="${ROOT_DIR}/local-libs/libxray"

mkdir -p "${WORK_DIR}" "${OUTPUT_DIR}"

require_command() {
  local command_name="$1"
  local install_hint="$2"

  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Required command '${command_name}' was not found." >&2
    echo "${install_hint}" >&2
    exit 1
  fi
}

require_command "git" "Install Git and make sure it is available in PATH."
require_command "python3" "Install Python 3 and make sure it is available in PATH."
require_command "go" "Install Go, for example: brew install go"
require_command "gomobile" "Install gomobile, for example: go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init. Make sure GOPATH/bin is in PATH."

if [ ! -d "${WORK_DIR}/libXray/.git" ]; then
  git clone https://github.com/XTLS/libXray.git "${WORK_DIR}/libXray"
fi

cd "${WORK_DIR}/libXray"
git fetch --tags

if ! git rev-parse -q --verify "refs/tags/${LIBXRAY_TAG}" >/dev/null; then
  echo "libXray tag '${LIBXRAY_TAG}' was not found." >&2
  echo "Available tags:" >&2
  git tag --list 'v*' >&2
  exit 1
fi

git checkout "${LIBXRAY_TAG}"

python3 build/main.py android

FOUND_AAR="$(find . -name '*.aar' -type f | head -n 1)"
if [ -z "${FOUND_AAR}" ]; then
  echo "libXray build completed, but no AAR was found." >&2
  exit 1
fi

cp "${FOUND_AAR}" "${OUTPUT_DIR}/LibXray.aar"
echo "Copied ${FOUND_AAR} to ${OUTPUT_DIR}/LibXray.aar"
