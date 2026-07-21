#!/usr/bin/env bash
#
# The libXray tag every platform builds from. Sourced by build-libxray-{android,
# apple,desktop}.sh so Android, iOS/macOS and the desktop sidecar cannot drift
# apart — they share one xray-core, and a mismatch means the same config behaves
# differently per platform.
#
# Override for a one-off build with LIBXRAY_TAG=... in the environment.

LIBXRAY_TAG="${LIBXRAY_TAG:-v26.7.11}"
