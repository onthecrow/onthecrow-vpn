#!/usr/bin/env bash
#
# Privileged macOS VPN wrapper for the OnthecrowVPN desktop app.
# Runs as root (invoked via `osascript ... with administrator privileges`).
#
# Responsibilities (everything that needs root):
#   1. Exclude the proxy server IP from the tunnel (host route via the real
#      gateway) so Xray's own uplink doesn't loop back into the TUN.
#   2. Start the libXray desktop sidecar, which creates the utun interface and
#      runs Xray-core (vless / hysteria2).
#   3. Once the utun is up, redirect all traffic into it (split-default
#      0/1 + 128/1, which beats the existing default route while leaving it
#      intact for the excluded server host route).
#   4. Point system DNS at a public resolver so lookups are tunneled.
#   5. Signal readiness, then wait for a stop sentinel and revert everything.
#
# Args:
#   $1 sidecar binary path
#   $2 run.json path (libXray desktop run config)
#   $3 tun name (e.g. utun9)
#   $4 server host/IP (to exclude from the tunnel)
#   $5 work dir (sentinels + logs; created by the GUI)
#
set -u

SIDECAR="$1"
RUN_JSON="$2"
TUN_NAME="$3"
SERVER_HOST="$4"
WORK_DIR="$5"

LOG="${WORK_DIR}/sidecar.log"
READY="${WORK_DIR}/ready"
STOP="${WORK_DIR}/stop"
ERROR="${WORK_DIR}/error"

log() { echo "[vpn-macos] $*" >>"${LOG}" 2>&1; }
fail() { log "ERROR: $*"; echo "$*" >"${ERROR}"; cleanup; exit 1; }

DNS_SERVICE=""
DNS_ORIGINAL=""
IPV6_MODE=""
SERVER_IP=""
GATEWAY=""
SIDE_PID=""

resolve_ip() {
  local host="$1"
  if [[ "${host}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "${host}"; return
  fi
  # Resolve via the system resolver (still on the physical link at this point).
  dscacheutil -q host -a name "${host}" 2>/dev/null | awk '/ip_address:/{print $2; exit}'
}

default_gateway() { route -n get default 2>/dev/null | awk '/gateway:/{print $2; exit}'; }
default_iface()   { route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}'; }

dns_service_for_iface() {
  local dev="$1"
  networksetup -listallhardwareports | awk -v dev="${dev}" '
    /Hardware Port:/{port=$0; sub(/Hardware Port: /,"",port)}
    /Device:/{ if ($2==dev) { print port; exit } }'
}

cleanup() {
  log "cleanup start"
  if [ -n "${SIDE_PID}" ]; then
    kill -TERM "${SIDE_PID}" 2>/dev/null
    for _ in 1 2 3 4 5; do kill -0 "${SIDE_PID}" 2>/dev/null || break; sleep 0.4; done
    kill -KILL "${SIDE_PID}" 2>/dev/null
  fi
  route -n delete -net 0.0.0.0/1 -interface "${TUN_NAME}" 2>/dev/null
  route -n delete -net 128.0.0.0/1 -interface "${TUN_NAME}" 2>/dev/null
  if [ -n "${SERVER_IP}" ] && [ -n "${GATEWAY}" ]; then
    route -n delete -host "${SERVER_IP}" "${GATEWAY}" 2>/dev/null
  fi
  if [ -n "${DNS_SERVICE}" ]; then
    if [ -z "${DNS_ORIGINAL}" ] || [[ "${DNS_ORIGINAL}" == *"aren't any"* ]]; then
      networksetup -setdnsservers "${DNS_SERVICE}" "Empty" 2>/dev/null
    else
      networksetup -setdnsservers "${DNS_SERVICE}" ${DNS_ORIGINAL} 2>/dev/null
    fi
    # Restore IPv6 to its previous mode.
    case "${IPV6_MODE}" in
      Off) networksetup -setv6off "${DNS_SERVICE}" 2>/dev/null ;;
      *)   networksetup -setv6automatic "${DNS_SERVICE}" 2>/dev/null ;;
    esac
  fi
  rm -f "${READY}"
  log "cleanup done"
}

trap 'cleanup; exit 0' INT TERM

mkdir -p "${WORK_DIR}"
: >"${LOG}"
rm -f "${READY}" "${STOP}" "${ERROR}"

GATEWAY="$(default_gateway)"
IFACE="$(default_iface)"
[ -n "${GATEWAY}" ] || fail "no default gateway found"
log "default gateway=${GATEWAY} iface=${IFACE}"

SERVER_IP="$(resolve_ip "${SERVER_HOST}")"
[ -n "${SERVER_IP}" ] || fail "could not resolve server host ${SERVER_HOST}"
log "server ${SERVER_HOST} -> ${SERVER_IP}"

# 1. Exclude server from the tunnel.
route -n add -host "${SERVER_IP}" "${GATEWAY}" >>"${LOG}" 2>&1 || \
  route -n change -host "${SERVER_IP}" "${GATEWAY}" >>"${LOG}" 2>&1 || \
  fail "failed to add server exclusion route"

# 2. Start the sidecar (creates utun + runs Xray).
"${SIDECAR}" -configPath "${RUN_JSON}" >>"${LOG}" 2>&1 &
SIDE_PID=$!
log "sidecar pid=${SIDE_PID}"

# 3. Wait for the utun interface, then redirect traffic into it.
ok=""
for _ in $(seq 1 50); do
  if ifconfig "${TUN_NAME}" >/dev/null 2>&1; then ok=1; break; fi
  kill -0 "${SIDE_PID}" 2>/dev/null || fail "sidecar exited before ${TUN_NAME} appeared"
  sleep 0.2
done
[ -n "${ok}" ] || fail "${TUN_NAME} did not appear"
log "${TUN_NAME} is up"

route -n add -net 0.0.0.0/1 -interface "${TUN_NAME}" >>"${LOG}" 2>&1 || fail "add 0/1 route failed"
route -n add -net 128.0.0.0/1 -interface "${TUN_NAME}" >>"${LOG}" 2>&1 || fail "add 128/1 route failed"

# 4. Force DNS through the tunnel.
if [ -n "${IFACE}" ]; then
  DNS_SERVICE="$(dns_service_for_iface "${IFACE}")"
  if [ -n "${DNS_SERVICE}" ]; then
    DNS_ORIGINAL="$(networksetup -getdnsservers "${DNS_SERVICE}" 2>/dev/null | tr '\n' ' ')"
    networksetup -setdnsservers "${DNS_SERVICE}" 1.1.1.1 8.8.8.8 >>"${LOG}" 2>&1
    log "dns service=${DNS_SERVICE} original=[${DNS_ORIGINAL}] -> 1.1.1.1"

    # Only IPv4 is tunneled here; disable IPv6 on the service for the session so
    # AAAA traffic (YouTube etc.) doesn't leak out the physical link.
    IPV6_MODE="$(networksetup -getinfo "${DNS_SERVICE}" 2>/dev/null | awk -F': ' '/^IPv6:/{print $2; exit}')"
    networksetup -setv6off "${DNS_SERVICE}" >>"${LOG}" 2>&1
    log "ipv6 mode was [${IPV6_MODE}] -> off"
  fi
fi

# 5. Ready, then wait for the stop sentinel.
touch "${READY}"
log "ready"
while [ ! -f "${STOP}" ]; do
  kill -0 "${SIDE_PID}" 2>/dev/null || { log "sidecar died unexpectedly"; break; }
  sleep 0.5
done

cleanup
exit 0
