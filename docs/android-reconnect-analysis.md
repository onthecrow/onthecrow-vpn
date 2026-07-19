# Android reconnect — three-expert analysis

**The problem.** After a network change (Wi-Fi ↔ mobile, both directions) and after waking from deep sleep / Doze, the VPN often does not come back up, or comes back and dies again shortly after. The app is a Kotlin Multiplatform VPN client using Android `VpnService` + libXray (xray-core, Go) with hysteria2 / vless over QUIC (UDP). The `VpnService` runs in a separate `:vpn` OS process. Device under test: Android 16 (SDK 36), minSdk 26.

**The goal.** Re-establish must work ~100 % of the time after a network change and after Doze exit. Reliability is priority #1; minimal reconnect latency is priority #2.

**How this document was produced.** Three independent experts — an Android/Doze specialist, a Go/libXray specialist, and a systems/network specialist — each read the current working tree, the vendored Go sources (`.libxray-build/libXray`, `xray-core@v1.260327.0`, `apernet/quic-go v0.59.1`) and the real field logs (`vpn-debug (13).log` + `xray (4).log`, `vpn-debug (12).log` + `xray (3).log`, and older ones). Each then wrote an adversarial grooming note attacking the other two reports and their own. This document opens with the technical lead's synthesis — the contradictions resolved, one design to ship — and preserves all six source documents verbatim below.

---

## Executive summary

**The single most important finding: the health probe lies, and it lies precisely in the state it exists to detect.** `probeTunnel` (`OnthecrowVpnService.kt:462-474`) opens an *unconnected* `DatagramSocket`, sends a DNS query to `1.1.1.1:53`, and accepts *any* datagram of length > 0 as success — it never `connect()`s, never verifies it is on the tun, and never checks the DNS transaction ID it built. When a probe is issued shortly after `Builder.establish()` returns, the datagram egresses on the **physical** network, gets a legitimate answer, and the probe reports "healthy" on a tunnel that is dead. This is proven twice over: (a) correlation — the "OK" probes at `01:39:39.484` and `01:40:03.687` have **no matching** `proxy/tun: processing from udp:10.77.0.2:… to udp:1.1.1.1:53` entry in `xray (3).log`, while every failing probe does; (b) physics — those probes completed in **38-39 ms**, while every probe that genuinely traversed tun → gVisor → hysteria2 → Ireland (`78.17.84.51`) → 1.1.1.1 → back cost **158-226 ms**. All three fast ones were sent 71-135 ms after `establish()`; all the honest ones ≥ 250 ms after. **Consequence: recovery has repeatedly declared victory mid-failure, and no measurement taken so far can be trusted.** Fix the sensor before anything else, or you cannot evaluate any other change.

**The second root cause: the ~30 s stall is quic-go's idle timer, not a permanent pool cache — and the timer does not run while the CPU is suspended.** xray-core's hysteria transport keeps a process-global client pool (`manger`, `dialer.go:437`) keyed on `dest.NetAddr()` (`dialer.go:446`). `StopXray` does not touch it (`xray/xray.go:106-115` closes only the `core.Instance`). But `dial()` *does* self-heal (`dialer.go:149-156`): it re-dials as soon as `status()` reports `StatusInactive`, and `status()` (`dialer.go:129-139`) is nothing but a `select` on `c.conn.Context().Done()`. That context is cancelled only by the QUIC idle timer, which defaults to **30 s** (`dialer.go:245-247`) with **keepalive disabled** — the default is literally commented out (`dialer.go:248-250`), and `XrayConfigSanitizer.kt:51-110` never emits `streamSettings.finalmask.quicParams`. So the pool pins a corpse for 30 s of *awake* time. Go's runtime timers ride `CLOCK_MONOTONIC`, which does not advance across Android suspend, so post-Doze the 30 s countdown **restarts at resume**: measured `20:40:02.834 − 20:39:32.751 = 30.08 s` from screen-on to the first fresh `dialing to udp:78.17.84.51:1935`. Three in-process xray restarts inside that window (`20:39:34.338`, `20:39:39.439`, `20:39:58.567`) produced **no dial at all**. The project's founding premise — "only a fresh process clears the pool" — is therefore false as stated: the process kill works by bypassing a timer, not by clearing a permanent cache.

**The third root cause: recovery is routed across a process boundary to a process that is normally dead.** `recover()` (`OnthecrowVpnService.kt:405-413`) sends a broadcast (`VpnStatusBroadcast.kt:45-48`) to a **runtime-registered** receiver in the main process (`PlatformVpnController.android.kt:42-44`). A runtime receiver cannot start a process. In `vpn-debug (13).log` the main process (pid 26986) logs last at `01:40:42.462` and the next main pid (5982) appears only at `02:16:00` because the *user* opened the app — a ~35 minute gap while the tunnel was up. And when the main process *is* alive but cached, delivery is deferred: the four `Connected` broadcasts sent at `01:39:34.363 / 01:39:39.444 / 01:39:58.579 / 01:40:03.648` all arrived in one batch at `01:40:42.460-.462` — **68 s / 63 s / 44 s / 39 s**. Even on arrival, `lastXrayJson` is an in-memory field (`PlatformVpnController.android.kt:27-28`) and is `null` in any freshly-restarted main process, so the handler logs "recover request ignored — no cached config". Three independent ways to fail, stacked.

**Fourth: the tun is destroyed and rebuilt on every recovery, and it must not be.** `runConnect` calls `stopTunnel()` (`OnthecrowVpnService.kt:188`), which closes the master `tunInterface` (`:340-346`). The class comments at `:60-63`, `:227-231` and `:290` claim the opposite and are **false** — the `keepTun` path was deleted from the tree. This costs: a measured **22.2 s of completely unprotected traffic** on the process-kill path (`02:16:09.861` kill → `02:16:32.109` new tun), the establish-window that makes the probe lie, and a deterministic **fd-number recycling race** (`101/102/103` reused every cycle) against gVisor reader goroutines that `AndroidTun.Close()` — a verified no-op at `tun_android.go:47-49` — never stops.

**Fifth: `START_STICKY` is not a restart engine here.** Zero `"sticky restart"` hits across every `vpn-debug*.log`. The observed evidence is a 20.2 s gap after a self-kill ended by the user pressing reconnect — which proves *unbounded and escalating* restart latency (AMS `SERVICE_RESTART_DURATION` with ×4 escalation), not a hard failure. Either way: unusable.

**Sixth: `setUnderlyingNetworks()` routes nothing.** The doc comment at `OnthecrowVpnService.kt:573-577` ("so protected sockets follow it") is wrong. It writes `NetworkAgent` metadata only — transports, metered/roaming/suspended, bandwidth, `NetworkStats` attribution. A `protect()`ed socket follows the **system default network**, always. This wrong model is why the fix effort was aimed at socket binding and underlying-network freshness, which is not where the failure lives. (The re-query must still be restored — as a *detector* of handovers slept through, proven by `01:39:34.280 underlying refresh: STALE 111 -> 101` on a transition where `onAvailable` never fired.)

### The recommended design, in one paragraph

**Recovery lives entirely inside the `:vpn` process, never crosses a process boundary, never closes the tun, never kills the process, and never depends on a timer that freezes across suspend.** Concretely: (1) make the probe honest — `connect()` the socket and assert `localAddress == 10.77.0.2`, verify the DNS txid and QR bit; (2) fork libXray/xray-core to export a `CloseAll()` that empties the hysteria pool under the *client* lock, and call it from `PlatformXrayEngine.stop()`; (3) add `runRedial()` = `stopXray → CloseAll → setTunFd(freshDup) → start`, leaving `tunInterface` untouched — measured cost of a genuine fresh dial is **190-272 ms**; (4) delete the cross-process broadcast recovery path; (5) trigger on validated-capability changes, link-property changes, idle-mode exit, screen-on and keepalive failure, **regardless of screen state**, under a wakelock and a single mutex; (6) keep the process-kill path as tier 4 only, and as the *only* legal path for a user-initiated config switch. The one load-bearing dependency is (2). If the fork is blocked, the target is not reachable — everything degrades to waiting out a timer that does not run while the device is asleep.

---

## Final converged solution

Strictly ordered. **Steps 0-3 are the fix.** Nothing after step 1 is *measurable* until step 0 lands, and nothing after step 2 *works* until step 1 lands — the log proves that in-process xray restarts without a pool flush are futile (three restarts, zero dials, `xray (3).log 20:39:34.338 / 20:39:39.439 / 20:39:58.567`).

### Step 0 — Instrumentation (ship first, one build, ~30 minutes)

Three log lines that settle four open disputes in a single field run.

| # | Change | File | Settles |
|---|---|---|---|
| 0.1 | Log `socket.localAddress` on every probe | `OnthecrowVpnService.kt:462-474` | Whether the probe escapes the tun (the false-positive mechanism) |
| 0.2 | Log the full emitted `runtimeJson` once | `OnthecrowVpnService.kt:245` | Whether `streamSettings.finalmask.quicParams` exists today (insert vs. merge) — **the one unverified config field in this document** |
| 0.3 | Log the dup'd fd number on every `setTunFd` and every close | `OnthecrowVpnService.kt:227-250`, `:299-304` | The fd-recycling race |

Field test: handover both directions, plus a Doze exit after ≥ 10 minutes screen-off. Pull `vpn-debug` and `xray` logs.

### Step 1 — Make the sensor honest

**File:** `OnthecrowVpnService.kt:462-474`.

```kotlin
private fun probeTunnel(timeoutMs: Int): Boolean = runCatching {
    DatagramSocket().use { s ->
        s.connect(InetSocketAddress(InetAddress.getByName("1.1.1.1"), 53))
        if ((s.localAddress as? Inet4Address)?.hostAddress != TUN_ADDRESS) return@use false
        s.soTimeout = timeoutMs
        val q = buildDnsQuery("cloudflare.com", PROBE_DNS_TXID)
        s.send(DatagramPacket(q, q.size))
        val r = DatagramPacket(ByteArray(512), 512)
        s.receive(r)
        r.length >= 12 &&
            ((r.data[0].toInt() and 0xFF) shl 8 or (r.data[1].toInt() and 0xFF)) == PROBE_DNS_TXID &&
            (r.data[2].toInt() and 0x80) != 0
    }
}.getOrElse { false }
```

**Mechanism.** `connect()` on a UDP socket forces the kernel to bind a source address *now*, from the route it will actually use, and to filter incoming datagrams by the 5-tuple. Reading `localAddress` afterwards therefore tells you definitively which interface the packet leaves on. Hoist `10.77.0.2` into a `TUN_ADDRESS` constant shared with the `Builder` at `:193` so the two can never drift. The txid + QR check closes the "any stray datagram counts" hole.

**Why this is correct regardless of the mechanism debate.** Two candidate explanations exist for the escape (async netd per-UID rule installation after `establish()` returns; ConnectivityService re-registering the VPN `NetworkAgent`), and a third alternative cause was raised in grooming (Private DNS / the `com.google.android.gms` split-tunnel exclusion producing local answers). The source-address assertion is correct under **all** of them. Additionally: **never probe within 500 ms of an `establish()`**, and never treat a probe issued before the first post-establish network callback as authoritative. After step 3 the tun stops being rebuilt, so this window largely ceases to exist.

**Trade-off:** the honest probe will produce false *negatives* where the old one produced false positives (e.g. a transient upstream stall). At ~250 ms per redial, that is the right direction to be wrong in.

### Step 2 — Fork libXray/xray-core and export a real pool flush

**This is the load-bearing change.** `manger` is a package-level unexported var (`dialer.go:437`), `clientManager.clean()` is unexported (`dialer.go:428-435`, and it never deletes entries — it only calls `close()` on already-inactive clients), and the whole `transport/internet/hysteria` package exports only `Dial` and `Listen`. There is no JSON knob, no outbound tag, and no dialer-controller trick that reaches it. A fork is the only complete option.

**Where:** `.libxray-build/libXray` (full source present), `scripts/build-libxray-android.sh` (already builds xray from source), `.libxray-build/libXray/onthecrow_convert/` (a custom Go package already exists, so the machinery is proven).

```go
// transport/internet/hysteria/dialer.go  (fork)
func CloseAll() {
    manger.mutex.Lock()
    clients := make([]*client, 0, len(manger.m))
    for k, c := range manger.m {
        clients = append(clients, c)
        delete(manger.m, k)
    }
    manger.mutex.Unlock()
    for _, c := range clients {
        c.closeLocked() // takes c's OWN mutex; must be idempotent
    }
}
```

**The locking detail is not optional.** `c.close()` (`dialer.go:141-147`) nils `conn` / `pktConn` / `udpSM`. If another goroutine is mid-`c.tcp()` / `c.udp()` on that client, a naive flush is a **nil-deref race**, and a Go panic in `:vpn` is an uncatchable process death — which we have established `START_STICKY` will not recover from. Take the client lock, not just `manger.mutex`, and make `close()` idempotent.

**Ordering:** call it from `PlatformXrayEngine.android.kt` `stop()` (`:132-145`) **after** `stopXray` returns, or in-flight outbound handlers repopulate the map.

**Coverage:** if vless-over-QUIC is in scope for this app, `transport/internet/quic` has its own `clientConnections` process-global with the same shape. Patch both, or the fix is config-dependent.

**What it buys beyond the connection.** `dialer.go:449-465` shows that on a pool *hit* only `c.setCtx(ctx)` runs — `config` (the **auth string**), `tlsConfig`, `socketConfig` **and** `quicParams` were all captured at first insert (`:453-461`) and are never refreshed. So the flush is also the fix for silent wrong-credential reuse on the same `host:port`, and it is what makes step 6's `quicParams` injection able to take effect on an already-pooled address.

**Cost accepted:** a `replace github.com/xtls/xray-core => <fork>` in `.libxray-build/libXray/go.mod` is a permanent maintenance obligation across upstream bumps. Accept it; it is the price of the reliability target.

**Rejected alternative — pool-key rotation** (alternate hostname ↔ IP literal, or two ports). The key is `dest.NetAddr()`, computed at `dialer.go:449` **before** udpHop port selection, so `udpHop.ports` does *not* mint a new key. And the observed dial target is a bare IP literal on a single port (`xray (4).log:12`, `udp:78.17.84.51:1935`) — there is nothing to alternate. It also leaves the old entry holding a live socket and goroutines until the 30 s cleaner. Emergency stopgap only, and not one actually available here.

### Step 3 — Keep the tun; re-dial only xray

**Files:** `OnthecrowVpnService.kt:188`, `:227-250`, `:299-304`, `:340-346`; `PlatformXrayEngine.android.kt:103`.

1. **Add `runRedial()`**: `stopXray()` → `CloseAll()` → `setTunFd(freshDup)` → `xrayEngine.start(runtimeJson)`. It **never** calls `stopTunnel()`. `runConnect` / `stopTunnel` remain for user connect, user disconnect, revoke and fatal failure only.
2. **Do not close the dup'd fd** (`:302`). `AndroidTun.Close()` is a verified no-op (`tun_android.go:47-49`) and `core.Instance.Close()` does not join the gVisor `fdbased` reader goroutines, while the logs show fd numbers recycling deterministically. Leak one fd per restart — bounded by the recovery count, reclaimed at process death. A timed 200 ms delay before closing is a race you cannot prove you won; reject it.
3. **Always `setTunFd(freshDup)` before every `start()`, and log the number.** The env var `xray.tun.fd` persists for the process lifetime (`xray/xray.go:51-53`, read lazily at `tun_android.go:28`). Combined with (2), forgetting to re-set it fails **silently** on a stale-but-still-open fd. Assert against it.
4. **Make `registerProtectControllers` idempotent** — `PlatformXrayEngine.android.kt:103` currently runs inside every `start()` and appends two fresh `Proxy` objects to xray's process-global controller slice (`system_dialer.go:202-207`), which is iterated per socket at `system_dialer.go:67`, inside `lc.Control`, **before bind**. After N in-process restarts that is 2N JNI round-trips on every dial; worse, if a handler ever returns `false` the socket may be treated as unprotected, and an unprotected upstream socket routes **back into the tun** — an encapsulation loop that looks exactly like "tunnel dead" while burning CPU. A process-level `AtomicBoolean` guard. **Blocking prerequisite for step 3.**
5. **Delete the false doc comments** at `:60-63`, `:227-231`, `:290`, and correct `:573-577`. They are what caused two of three experts to misread the current behaviour.

**What this removes:** the 22.2 s unprotected-traffic window per recovery (a *security* defect, not a latency one), the establish-window that makes the probe lie, the fd-number use-after-free, the destruction of every app's sockets on every recovery, and the slowest part of the current path (`Vpn.establish()` → NetworkAgent re-registration → netd rule reinstall).

### Step 4 — Recovery lives entirely in `:vpn`

**Delete from the critical path:** `sendRecoverRequest` / `registerRecoverRequest` (`VpnStatusBroadcast.kt:45-59`) and `onRecoverRequested` / `reconnect` (`PlatformVpnController.android.kt:47-77`). The main process becomes a UI mirror only.

**Split teardown from recovery.** `runDisconnect` (`OnthecrowVpnService.kt:306-326`) becomes `runUserDisconnect()` — the **only** caller of `paramsStore.clear()` (`:311`) — and `runRecoveryRestart()`, which keeps the persisted params and keeps the status at `Connecting`. Recovery must never emit `Disconnected`: besides destroying its own fallback, that status drives `VpnSyncWorker` (`feature/connection/logic-impl/.../VpnSyncWorker.kt:66-76`) to reset `activeKey`, so a genuine remote config change arriving during a recovery is silently swallowed.

Move `recordRecoveryKill()` (`:410`) to *after* a confirmed-successful recovery, so a failed attempt does not burn the 6 s debounce token.

**The ladder** — single `Mutex`, critical section `NonCancellable`, `PARTIAL_WAKE_LOCK` held across the whole thing with a 20 s timeout and released in `finally` (add `WAKE_LOCK` to `androidApp/src/main/AndroidManifest.xml` — verified absent today):

| Tier | Action | Expected cost |
|---|---|---|
| **T0** | `refreshUnderlyingFromSystem()` → `applyUnderlyingNetworks(...)` → honest probe | ~50 ms |
| **T1** | `runRedial()` (stopXray → CloseAll → setTunFd → start) → re-probe at 500 ms / 1 s / 2 s. Retry once. | ~250-400 ms per attempt (measured fresh dial: 190 ms and 272 ms) |
| **T2** | Full in-process rebuild: `stopTunnel()` + `runConnect()` | ~1-2 s, reintroduces a short leak window |
| **T3** | Last resort: persist params → `Process.killProcess` → restart via `setExactAndAllowWhileIdle(now + 1500 ms)` → **manifest** receiver in `:vpn` → load `ConnectionParams` → `startForegroundService(ACTION_CONNECT)` | seconds to minutes; rate-limited |

**Attempt budget and backoff.** A correct probe still cannot distinguish "upstream dead" from "server down" from "credential rejected". Cap the ladder (e.g. 3 full passes with exponential backoff to 60 s) and surface a terminal `Error` state to the user rather than hot-looping against a genuinely down server.

**Handle `onRevoke()` as terminal** — another VPN app or the user in Settings revoking us is not a recoverable network failure, and today it is unmodelled.

### Step 5 — The trigger set (all screen-state-independent)

**Files:** `OnthecrowVpnService.kt:508-533` (receivers), `:591-645` (network callback).

| Trigger | Change | Why |
|---|---|---|
| `onCapabilitiesChanged` with `NET_CAPABILITY_VALIDATED` becoming true on a network `!= lastUnderlying` | **Replaces** bare `onAvailable` (`:603-614`) as the recovery trigger | Cellular typically appears unvalidated and validates 1-3 s later; re-dialling at `onAvailable` burns the attempt on a link that cannot carry the QUIC handshake yet |
| `onLinkPropertiesChanged` | Act when addresses/routes change on the current underlying — currently log-only (`:617-619`) | The **only** signal for a same-netId route change (Wi-Fi reassociation, IPv6 prefix change, inter-RAT handover keeping the netId). Today such a change produces no recovery at all |
| `onLost(current)` | Call `applyUnderlyingNetworks(null)`; **stop nulling `lastUnderlying`** — track the last *available* network and compare netIds (`:621-626`) | Otherwise we keep advertising a dead network's transports, and a Wi-Fi flap (`02:16:09.625/.705` → `underlying changed: null -> 101`) reads as a change and triggers a full recovery for a network that never changed |
| `ACTION_DEVICE_IDLE_MODE_CHANGED` exit | **New** receiver alongside the screen receiver | This, not `SCREEN_ON`, is the real Doze-exit signal; it fires without the screen turning on |
| `ACTION_SCREEN_ON` | Keep, as a fast path (`:519-523`) | |
| Keepalive failure | 2 consecutive failures → ladder (`:434-450`) | |

Also: **restore `refreshUnderlyingFromSystem()`** (deleted in the working tree) and run it at the top of every recovery and every idle-exit. Scan `cm.allNetworks` for `NOT_VPN + INTERNET`, preferring `VALIDATED`; `activeNetwork` is useless because we *are* the VPN. Justification: `01:39:34.280 underlying refresh: STALE 111 -> 101` (plus identical lines at `00:46:22.874` and `01:11:20.311`) proves callbacks are missed across Doze — **as a detector, not a routing fix**.

**Register the network callback once per process, before `startXrayOnTun`, and never unregister until `onDestroy`.** Today `startMonitoring()`/`stopMonitoring()` (`:483`, `:492-506`) cycle the registration and swallow unregister failures in `runCatching { }`; AOSP enforces a per-UID cap of 100 concurrent `NetworkRequest`s and throws `TooManyRequestsException` past it — a real ceiling in a long-lived `:vpn` process doing in-process recovery.

**Remove the screen-off gate** at `:609-613`. A handover with the screen off is exactly the case that must work, and post-step-3 it costs ~250 ms of CPU.

### Step 6 — Defence in depth (ship, but nothing depends on it)

- **`streamSettings.finalmask.quicParams: { maxIdleTimeout: 8, keepAlivePeriod: 3 }`**, **merged, never overwritten**, in `XrayConfigSanitizer.kt:51-110`. ⚠️ **UNVERIFIED — gated on step 0.2.** The JSON tags (`infra/conf/transport_internet.go:630-643`), the `finalmask` nesting (`:1719-1723`, `:1734`), the bounds `maxIdleTimeout ∈ [4,120]` / `keepAlivePeriod ∈ [2,60]` (`:1951-1956`) and the consumption path (`dialer.go:222-247`) were all read from source, but **nobody has seen the emitted 1101-byte config**, and an unknown key passed silently through `finalmask` would look identical to success. Log `runtimeJson` once, confirm the field parses, then ship. Merge is mandatory: `share/hysteria_mask.go:12` only allocates `quicParams` when the share link carries bandwidth/ports, and clobbering it silently downgrades brutal congestion control to BBR. Values 8/3 rather than 5/2 — a 5 s gap on a lossy cell link tears down a *healthy* connection and each teardown costs a full re-handshake plus hysteria auth. **What it does not do:** it does nothing in Doze (frozen timers), it cannot hold a NAT mapping open through deep sleep, and it cannot rescue an entry already pooled (params are captured at insert, `dialer.go:448-461`). It helps the screen-on handover case only.
- **IPv6.** Add `addRoute("::", 0)` first so v6 **fails closed** rather than leaking (`OnthecrowVpnService.kt:190-201` is IPv4-only today, and Android does not blackhole address families a VPN omits — on cellular, IPv6 is nearly always present and Happy Eyeballs prefers it). Add `addAddress("fd00:1:2:3::1", 128)` and a v6 DNS server **only after** verifying that `proxy/tun` and the hysteria outbound handle v6 destinations in this build. Ship this **separately, after** the reconnect work is measured — it is a reliability-regression risk (a 250 ms Happy Eyeballs penalty per connection if v6 egress is broken) and it will confound the field measurement.
- **`BOOT_COMPLETED`** manifest receiver + `RECEIVE_BOOT_COMPLETED` (absent today): restore the tunnel on boot if `ConnectionParams` exist.
- **In-app recommendation for always-on VPN + lockdown**, deep-linking to `Settings.ACTION_VPN_SETTINGS`, plus a HyperOS Autostart prompt. This is the strongest thing available — the system owns restart, and lockdown installs UID-range blackhole rules so every residual gap fails **closed** — but it cannot be granted programmatically and has no public read API. **Recommendation only; it does not count toward the target.**
- MTU 1500 → 1400 (`:191`). Proxied UDP near 1500 B cannot fit a QUIC DATAGRAM frame (quic-go clamps ~1200-1350), which exposes QUIC-over-QUIC (browser HTTP/3, all over the logs as `udp:…:443`), large DNS and WebRTC. Low priority, unconfirmed without a capture.

### Runtime fallback chain, end to end

```
trigger (validated-capability change | link-props change | idle-exit | screen-on | keepalive fail)
  → acquire PARTIAL_WAKE_LOCK (20 s timeout) + ladder Mutex, NonCancellable
  → T0  refreshUnderlyingFromSystem + applyUnderlyingNetworks + honest probe   (~50 ms)
       ↓ dead
  → T1  runRedial: stopXray → CloseAll → setTunFd(freshDup) → start
        re-probe at 500 ms / 1 s / 2 s; retry once                             (~250-400 ms)
       ↓ dead after 2 attempts
  → T2  stopTunnel + runConnect (full in-process rebuild)                      (~1-2 s)
       ↓ dead
  → T3  persist params → killProcess → setExactAndAllowWhileIdle(+1500 ms)
        → manifest receiver in :vpn → startForegroundService(ACTION_CONNECT)
       ↓ dead → backoff, terminal Error surfaced to the user
  → release wakelock in finally; record recovery success only on a confirmed OK probe
```

### What happens when the main process is dead

Nothing changes. It is not on the critical path at any tier from T0 to T2. Status broadcasts to it remain best-effort UI updates; their 39-68 s deferral is now cosmetic rather than fatal. `ConnectionParamsStore` in `:vpn` is the single source of truth for the config, and it is never cleared by a recovery path.

### What happens while dozing with the screen off

- **A handover while asleep** is acted on immediately if the `onCapabilitiesChanged`/`onLinkPropertiesChanged` callback is delivered — the screen-off gate is gone and the cost is ~250 ms of CPU under a wakelock.
- **Callbacks not delivered while dozing** (proven to happen: `STALE 111 -> 101` with no `onAvailable`) are caught by `ACTION_DEVICE_IDLE_MODE_CHANGED` exit, which runs `refreshUnderlyingFromSystem()` + probe + T1. This is the correct Doze-exit trigger; `SCREEN_ON` is merely a proxy for it and remains as a fast path.
- **The keepalive loop is not cancelled on `SCREEN_OFF`** (today `:513-518` cancels `tunnelJob`); it is **slowed to ~60 s**. This is best-effort by construction — `delay()` rides `CLOCK_MONOTONIC` and does not fire during suspend — and that is fine: the goal is to *bound* the dead window at ~60 s of awake time rather than "until the user unlocks", not to eliminate it.
- **Probe timeout drops from 1500 ms to 600 ms** (`:728`), because a needless redial now costs ~250 ms whereas 1.5 s of dead traffic at unlock is user-visible.
- The tunnel dying during deep Doze is **expected and acceptable** (carrier NAT mappings expire in 30-120 s and no keepalive can survive a frozen timer). The failure mode being eliminated is *dead but believed alive*, not *dead*.

### Leak / latency trade-offs

| | Today | After |
|---|---|---|
| Unprotected-traffic window per recovery | **22.2 s** measured (`02:16:09.861` → `02:16:32.109`); unbounded on failure | **0** at T0/T1 — the tun is never closed. Non-zero only at T2/T3, which should essentially never run |
| Screen-on handover recovery | ~30 s, or never | **~300-800 ms** (validated-trigger delay + ~250 ms redial) |
| Doze-exit recovery | ~30 s if it happens at all; often "until the user reconnects manually" | **~1 s from the wake event**; worst case bounded at ~60 s of awake time |
| Battery | 8 s polling loop while screen-on; nothing while off | Same while screen-on; ~60 s probe while off + one event-driven redial per genuine handover |

### Reliability argument — why this reaches ~100 %

The current design multiplies together six independent coin flips: the main process being alive, broadcast delivery latency, `lastXrayJson` surviving in memory, a legal background FGS start, `START_STICKY` restarting the service, and OEM autostart policy. Each is < 1, and today they are multiplied.

T0-T2 depend on **none of them**. They execute inside a process that is already a running foreground service holding the tun fd, the config, the network callback and the xray instance. They touch no FGS background-start restriction (Android 12/13/14/16), no background-activity-start rule, no `START_STICKY`, no broadcast dispatch, no exact-alarm quota, no battery-optimization dialog, no `runningAppProcesses` polling, and no main process. The only external dependency left is the `:vpn` process staying alive — which is exactly what a foreground service is for, and which the field logs confirm (pid 27094 survived while the main process died).

The second half of the argument is the sensor. With an honest probe, every tier's success is *verified* before the ladder stops, so "recovery declared victory on a dead tunnel" — the observed failure at `01:39:39.484` and `01:40:03.687` — becomes structurally impossible. With `CloseAll()`, T1's success no longer depends on a timer that does not run while the device is asleep. Those two changes together are what convert "sometimes" into "deterministically".

### Residual risks

1. **Step 2 blocked** (no appetite for a fork, or `manger` semantics differ from the two independent source reads). This is the single decision that determines whether the target is reachable. Without it, T1 degrades to waiting out a 30 s timer that does not run in suspend, and the design falls back to T3 with all of its Doze-restart problems and its leak window. **Not ~100 %.**
2. **`CloseAll()` racing an in-flight `c.tcp()` / `c.udp()`** → nil-deref panic → `:vpn` process death, from which `START_STICKY` will not recover. Mitigated by client-level locking and an idempotent `close()`; **must be stress-tested** by redialling in a loop under load.
3. **A Go panic anywhere in xray, or an OEM/LMK kill of `:vpn` itself.** Nothing app-side beats HyperOS if it decides to kill a foreground service. Only always-on VPN + lockdown genuinely covers this, and it is a user setting. T3 is the app-side backstop and it is deliberately weak.
4. **The probe still lying for an unmodelled reason** — e.g. Private DNS (DoT to `dns.google` from the resolver UID) or the `com.google.android.gms` split-tunnel exclusion producing local answers, which would explain the 38 ms probes independently of the establish window. The `localAddress` assertion is correct either way, but the diagnosis would need revising. The unexplained `01:39:49` → `01:39:58` window (probe healthy, then two failures 10 s and 19 s later on a just-verified tunnel) is still not fully accounted for.
5. **A second cached layer not audited** — `http3.Transport` holds its own connection state and nobody proved `c.close()` tears it down completely. Also: non-hysteria transports keep their own globals (`transport/internet/quic`'s `clientConnections`).
6. **`quicParams` not actually reaching the config** (step 6, ⚠️ unverified). Defence-in-depth only, so nothing breaks — but do not let anything become load-bearing on it.
7. **Server-side `max_idle_timeout` below our value** makes step 6 a no-op (`min()` wins). No server visibility.
8. **T3's exact-alarm quota** (~1 firing per 9-10 min in deep Doze), **AMS restart backoff** (escalating ×4 per restart, so T3 degrades the more it fires), and **HyperOS Autostart** gating manifest-receiver cold starts. Accepted, because the design is built so T3 essentially never runs. Note also: `USE_EXACT_ALARM` is Play-policy-restricted to alarm-clock/calendar apps, so a VPN declaring it is a plausible rejection — use `SCHEDULE_EXACT_ALARM` (user-revocable on 13+) and treat T3 as best-effort.

### Verification plan

**Instrumentation build (step 0) — required before anything else.**

1. **Probe honesty.** Reproduce the Doze-exit sequence. Expect: on every `tunnel probe OK`, a new log line showing `localAddress=10.77.0.2`. If any "OK" verdict shows a physical address (`192.168.x.x` or the rmnet address), the false-positive mechanism is confirmed and step 1 fixes it by construction. Cross-check every probe against `xray*.log` for a matching `proxy/tun: processing from udp:10.77.0.2:<port> to udp:1.1.1.1:53` (remember: xray timestamps run ~5 h behind). Any probe with a sub-100 ms latency and no matching tun entry is a liar.
2. **Config field.** Grep the logged `runtimeJson` for `finalmask` and `quicParams`. Determines whether step 6 is an insert or a merge — and whether it is viable at all.
3. **Main-process death.** `adb shell am kill com.onthecrow.onthecrowvpn.dev` (kills main, leaves the `:vpn` FGS), then toggle Wi-Fi. Expect a `SVC` recovery line with **no** matching `CTRL` line — confirming the broadcast path is dead. Cross-check with `adb shell dumpsys activity processes | grep onthecrowvpn`.
4. **Clock freeze.** Measure the delay from `SCREEN ON` to the next `dialing to udp:` in `xray.log` after ≥ 10 minutes of screen-off. ~30 s from screen-on (not from when the network actually changed) confirms the `CLOCK_MONOTONIC` model.

**After steps 1-5.**

| Test | Expected log sequence | Pass criterion |
|---|---|---|
| Wi-Fi → cell, screen on | `onCapabilitiesChanged VALIDATED <newNetId>` → `recovery T0` → probe fail → `runRedial` → xray `dialing to udp:78.17.84.51:1935` → `congestion bbr` → `probe OK localAddress=10.77.0.2` | 20/20 runs, < 1 s end to end, **no** `tun established` line (the tun was never rebuilt) |
| Cell → Wi-Fi, screen on | same | 20/20 |
| Handover, screen off | same sequence, with no `SCREEN_ON` in between | 10/10; verify with `adb shell dumpsys` that traffic works on unlock without any further recovery |
| Doze exit (≥ 30 min screen-off, `adb shell dumpsys deviceidle force-idle`) | `ACTION_DEVICE_IDLE_MODE_CHANGED exit` → `refreshUnderlying` → probe fail → `runRedial` → fresh dial | 10/10, < 1.5 s from the idle-exit line |
| Airplane mode 30 s on/off | ladder runs, backoff engages while there is no network, recovers on return | no hot-loop; a terminal `Error` never surfaces for a recoverable case |
| Redial under load (stress) | 200 × `runRedial` while a large download runs | zero `:vpn` process deaths — this is the `CloseAll()` race test |
| fd hygiene | `adb shell ls -l /proc/<vpn-pid>/fd \| wc -l` before and after 50 redials | monotonic growth of ≤ 1 per redial, no reuse of a live number |
| User disconnect | `runUserDisconnect` → `paramsStore.clear()` → kill | `paramsStore.clear()` appears **only** here, never in a recovery |
| Config switch | full process-kill path | new auth actually used (the `dialer.go:449-465` capture bug) |
| Leak audit | `tcpdump` on `rmnet1` / `ip rule` in a tight loop across a recovery | zero non-tunnel egress during T0/T1 |

**Log lines that should exist after the change and did not before:** `recovery T0/T1/T2 …`, `runRedial: stopXray → CloseAll → setTunFd(fd=…)`, `probe localAddress=…`, `idle mode exit → recover`, `caps validated <netId> — recovering`, `linkProps changed on current underlying — recovering`. **Log lines that should never appear again:** `send recover request`, `recover request ignored — no cached config`, and any `tun established` during a recovery.

---

## Points of disagreement and how they were settled

| # | The disagreement | Adjudication | Reasoning |
|---|---|---|---|
| **D1** | **Cause of the ~30 s stall.** Project premise: a permanently stale pool that only a fresh process clears. **B:** quic-go `MaxIdleTimeout = 30 s` with keepalive disabled. **C:** same, *plus* the timer rides `CLOCK_MONOTONIC`, which does not advance across Android suspend, so post-Doze it is counted from **CPU resume**. | **C, decisively.** B conceded in grooming. | `dial()` self-heals at `dialer.go:149-156` once `c.conn.Context()` is Done, so the pool is not a permanent cache — the brief's premise is false as stated. And C's measurement is not a coincidence: `20:40:02.834 − 20:39:32.751 = 30.08 s` measured **from screen-on**, not from the last received packet (which was ~3.5 min earlier in wall-clock). Go's runtime timers use `CLOCK_MONOTONIC`; `CLOCK_BOOTTIME` is the suspend-inclusive one and Go does not use it. B had no counter-measurement. |
| **D2** | **Does `finalmask.quicParams: {maxIdleTimeout, keepAlivePeriod}` fix it?** **B (original):** yes — "makes P1/P2/P4/P5/P6/P11 moot". **C:** it is a mitigation for the screen-on case only. | **C.** Demoted to defence in depth (step 6), and marked ⚠️ unverified. | Three independent refutations, none answered: (i) frozen timers mean it yields "5 s after wake", not "5 s after the link died", and a 2 s keepalive ping never fires in suspend so it cannot hold a carrier NAT mapping; (ii) `dialer.go:448-461` captures `quicParams` **only on pool insert**, so injecting it is a no-op for the corpse you are currently stuck behind — the exact case it was meant to fix; (iii) nobody has seen the emitted config, so the field itself is unverified. Values also revised 5/2 → 8/3 per B's own grooming: a 5 s threshold tears down healthy connections on a lossy cell link, and each teardown costs a full re-handshake. |
| **D3** | **Is the main process alive when recovery fires?** **A:** provably dead, and broadcasts are deferred. **B:** "inference — I cannot prove main-process death from log 13." **C:** cannot separate the cause. | **A. Now fact, not inference.** | Main pid **26986** logs last at `01:40:42.462`; the next main-process line is pid **5982** at `02:16:00`, and only because the *user* launched the app — a different pid means the process was killed, with a ~35 minute gap while the tunnel ran. Independently, the four `Connected` broadcasts at `01:39:34.363 / 01:39:39.444 / 01:39:58.579 / 01:40:03.648` were all delivered in one batch at `01:40:42.460-.462` — **68 / 63 / 44 / 39 s** of cached-process deferral. Whether the cause is LMK, HyperOS or the app freezer does not matter; the fix is identical. **Any design routing recovery through `sendBroadcast` to a runtime-registered receiver in the main process is dead on arrival** — which retires the current `recover()`, B's original "Tier 3", and C's F6 fallback as written. |
| **D4** | **What does `setUnderlyingNetworks()` do?** **A (F4):** it makes protected sockets follow the network, so a stale handle breaks routing. **C (P4):** false — it is `NetworkAgent` metadata only. | **C. A conceded in grooming.** | `Vpn.setUnderlyingNetworks()` mutates transports, metered/roaming/suspended/congested, bandwidth, `NetworkStats` attribution and what apps behind the VPN see from `getActiveNetworkInfo()`. It sets no fwmark and touches no routing table. A `protect()`ed socket follows the **system default network**, always. The re-query is still restored — but as a *detector* of handovers slept through (`01:39:34.280 STALE 111 -> 101`, with no `onAvailable`), not as a routing fix. The false doc comment at `:573-577` is corrected in the same commit, or the next reader re-derives the wrong design. |
| **D5** | **Is the tun kept open across recovery?** **A and B:** yes (both quote the class comment). **C:** no — it is closed on every recovery. | **C. Both others were reading a stale comment.** | `runConnect:188` calls `stopTunnel()`, which closes the master `tunInterface` at `:343`. The `keepTun` path was **deleted from the tree** — there is no in-process re-dial caller at all in the current build. The comments at `:60-63`, `:227-231` and `:290` are actively misleading. Consequence: A's and B's leak and latency estimates were all too optimistic, and B's "Tier 1 just re-enables existing code" is wrong — `runRedial()` has to be written from scratch. |
| **D6** | **Does the probe lie?** **C (P1):** yes — it escapes the tun and returns false "healthy". **A and B:** did not find it. | **C, and it is the highest-priority finding in the whole review.** | Two independent proofs. *Correlation:* the "OK" verdicts at `01:39:39.484` and `01:40:03.687` have no matching `proxy/tun: processing from udp:10.77.0.2` entry in `xray (3).log`, while every failing probe does. *Physics:* those probes completed in 38-39 ms; a DNS query genuinely traversing tun → gVisor → hysteria2 → Ireland → 1.1.1.1 → back costs 158-226 ms in the same log. All fast ones were sent 71-135 ms after `establish()`, all honest ones ≥ 250 ms after. Plus two enabling defects in the same 12 lines: the socket is never `connect()`ed (any stray datagram counts) and `PROBE_DNS_TXID` is built into the query and never checked in the response. The *mechanism* (async netd rule installation vs. NetworkAgent re-registration vs. Private DNS/GMS exclusion) is still open — but the `localAddress` assertion is correct under all of them, so it ships regardless. |
| **D7** | **Is an in-process tier ladder a good primary design?** **B (§3.2):** yes. **A (grooming):** refuted — it already shipped and already failed. | **Both, with sequencing.** The ladder is right; it must not ship before steps 1 and 2. | The `01:39:32 → 01:40:03` sequence in log 13 is an **earlier build** that had exactly this design (`recover (screen on) attempt 1/4`, `runConnect: restart=true forceFull=true tunUp=true`, `underlying refresh: STALE`, in-process xray restart, retry ladder). It lost to (a) the stale QUIC client and (b) the lying probe. The proof is in `xray (3).log`: three in-process restarts at `20:39:34.338`, `20:39:39.439`, `20:39:58.567` with **no** `dialing to udp:` between them. B did not know that log was a different build — the single biggest error across the three reports. The ladder becomes viable only *after* the honest probe and the real pool flush land. |
| **D8** | **B's Tier 0** — "set underlying networks, wait, re-probe". | **Rejected.** | It rests on D4's wrong model. `setUnderlyingNetworks` routes nothing, so Tier 0 is literally "wait for the idle timer" — up to 30 s of awake time, unbounded in suspend. It is a delay, not a recovery tier. T0 in the final design is retained only as a cheap *detector* (refresh + probe), never as a repair. |
| **D9** | **Does `START_STICKY` ever restart the service?** **A (P6) and the brief:** never. **A (grooming):** softened. | **A's softened version.** | The log proves `02:16:09.861 killing :vpn process` → **20.2 s** of nothing → `02:16:30.071` the *user* pressed disconnect. That proves "no restart within 20 s", not "never restarts". The likely mechanism is AMS service-restart backoff (`SERVICE_RESTART_DURATION` 1 s with ×4 escalation per restart inside `SERVICE_RESET_RUN_DURATION`), so after repeated self-kills the pending delay was plausibly 16 s or 64 s. Practical conclusion unchanged — **unusable as a recovery engine, because its latency is unbounded and grows with every use** — but the reason matters: it means *any* repeat-kill design, including A's own alarm-based F2, degrades the more it fires. |
| **D10** | **Restart engine when a restart is genuinely needed.** **A (F2):** exact alarm + manifest receiver as the *primary* mechanism. **B and C:** reject as primary. | **Rejected as primary; retained as T3.** A withdrew it in grooming. | Four stacked coin flips: (i) `setExactAndAllowWhileIdle` is rate-limited to ~1 per 9-10 min in deep Doze, so a handover during a long screen-off period gets one shot; (ii) it reintroduces the measured 22.2 s of unprotected traffic; (iii) the temporary-allowlist FGS exemption is not on the public list and is user-declinable, and it still races AMS restart backoff for the same component; (iv) HyperOS Autostart gates manifest-receiver cold starts entirely. Also `USE_EXACT_ALARM` is Play-restricted to alarm-clock/calendar apps. Four dependencies, each < 1, multiplied. |
| **D11** | **The dup'd tun fd.** **B (§3.5b):** delay the close ~200 ms. **C (F2):** never close it; leak it. | **C.** | A timed delay against un-joined gVisor reader goroutines is a race you cannot prove you won, and C *observed* the fd numbers recycling deterministically (`101/102/103` every cycle). `AndroidTun.Close()` is a verified no-op (`tun_android.go:47-49`) and `core.Instance.Close()` does not join the readers. One fd per restart, bounded by recovery count, reclaimed at process death. B's own grooming adds the footgun: because the leaked fd stays *open*, forgetting `setTunFd(freshDup)` fails silently rather than erroring — so the redial path must log and assert the fd number. |
| **D12** | **Pool-key rotation as a no-fork workaround.** **B and C (originally):** viable stopgap. | **Rejected for this deployment.** Both authors downgraded it in grooming. | The key is `dest.NetAddr()` computed at `dialer.go:449` **before** udpHop port selection, so `udpHop.ports` does not mint a new key — only the configured `address`/`port` string does. And the observed dial target is a bare IP literal on a single port (`xray (4).log:12`, `udp:78.17.84.51:1935`): there is nothing to alternate. It also leaves the old entry holding a live socket and goroutines until the 30 s cleaner, and entries are never deleted (`dialer.go:428-435`), so repeated handovers accumulate live QUIC connections on dead paths. |
| **D13** | **Add IPv6 to the tun.** **C (F4):** yes, it leaks today. **A and B (grooming):** correct, but a reliability-regression risk. | **Ship it, but separately and last.** | If the hysteria outbound has no v6 egress, claiming `::/0` blackholes IPv6 inside the tunnel and every Happy-Eyeballs app takes a ~250 ms fallback penalty per connection. Sequence: add the `::/0` **route** first (fail closed, which is correct for a VPN), add the address and v6 DNS only after verifying v6 through the tun inbound and the hysteria outbound. Do **not** bundle it into the recovery change — it will confound the field measurement. Note it is also a *reliability trap* in its current state: v6-capable apps keep working over the physical link while the tunnel is dead, which is why "it mostly works" and "it is completely broken" have coexisted in the field reports. |
| **D14** | **Is the hysteria pool clearable without a fork?** Brief: no, only a fresh process. **A:** could not verify. **B and C:** clearable only with a Go patch. | **B and C, from independent source reads.** | `manger` is a package-level unexported var (`dialer.go:437`), `clientManager.clean()` is unexported and never deletes entries (`dialer.go:428-435`), and the package exports only `Dial` and `Listen` (verified by `grep -E "^func [A-Z]"`). Nothing reachable from JSON or from a dialer controller touches it. `StopXray` provably cannot (`xray/xray.go:106-115` closes only the `core.Instance`). Two independent readers agreeing on unexported-symbol reachability is the strongest signal available short of building it. |
| **D15** | **Risks of the `CloseAll()` patch.** Raised only by B in grooming; C's version takes `manger.mutex` alone. | **B's locking is mandatory; C's sketch is unsafe as written.** | `c.close()` (`dialer.go:141-147`) nils `conn`/`pktConn`/`udpSM`. A goroutine mid-`c.tcp()`/`c.udp()` on that client gives a nil-deref, and a Go panic in `:vpn` is uncatchable process death — from which `START_STICKY` will not recover (D9). Take the **client** lock, collect-then-close outside `manger.mutex`, make `close()` idempotent, and call it strictly after `StopXray` returns or in-flight handlers repopulate the map. Also patch `transport/internet/quic`'s `clientConnections` if vless-over-QUIC is in scope, or the fix is config-dependent. |
| **D16** | **Where does the leak rank?** Only C costed it; A and B treated recovery as a latency problem. | **C. It is a correctness/security defect, and on its own sufficient grounds to delete the process-kill path from the common case.** | Measured 22.2 s (`02:16:09.861` kill → `02:16:32.109` new tun) with no VPN and no lockdown — all device traffic in the clear, on a recovery the user never asked for, and unbounded on a *failed* recovery. This is the strongest argument for the keep-the-tun design, independent of any reliability argument. |
| **D17** | **`registerProtectControllers` severity.** **B and C:** "N redundant protect calls, harmless today". **B (grooming):** worse than that. | **B's escalated version. It is a blocking prerequisite, not a cleanup.** | Each `start()` appends two *new* `Proxy` objects to a process-global slice (`PlatformXrayEngine.android.kt:103, 168-186`; `system_dialer.go:202-207`) iterated per socket inside `lc.Control`, **before bind**. At N=10 in-process restarts that is 20 JNI round-trips in the socket-creation hot path. And if any handler returns `false`, xray may treat the socket as unprotected — an unprotected upstream socket routes **back into the tun**, an encapsulation loop that looks exactly like "tunnel dead" while burning CPU. An `AtomicBoolean` guard, before step 3 ships. |
| **D18** | **Unadjudicated / open.** | Carried forward as residual risks, not blockers. | Whether the device-idle allowlist truly exempts background FGS starts on HyperOS; Android 15/16 `specialUse` FGS runtime restrictions and whether Play accepts `specialUse` subtype `vpn` (AOSP has no VPN FGS type, so there is no alternative); the exact netd/`establish()` ordering behind D6; the server's advertised `max_idle_timeout`; whether `http3.Transport` holds connection state that `c.close()` does not tear down; the MTU-1500 proxied-UDP concern (no capture); and the unexplained `01:39:49` → `01:39:58` window. None of these change the design — they change how much of the residual risk is left after it ships. |

### Additional findings each expert missed, folded into the design

- **`VpnSyncWorker` is a fourth actor on the tunnel** (`feature/connection/logic-impl/.../VpnSyncWorker.kt:66-76`) — it collects `vpnController.status` and resets `activeKey` on `Disconnected`, so a remote config change arriving during a recovery is swallowed. Fixed as a side effect of step 4, but only because recovery stops emitting `Disconnected`.
- **`NetworkRequest` registration is capped at 100 per UID** and `stopMonitoring()` swallows unregister failures (`:492-506`) — a real ceiling for a long-lived `:vpn` process doing in-process recovery. Register once per process; never unregister until `onDestroy`.
- **Demand-gating.** Nobody proposed reading the tun's rx counter (or `TrafficStats` for the VPN interface) as a free, zero-packet "is anyone actually behind this tunnel" signal. That is what makes a screen-off probe cadence affordable. Worth considering as a later refinement.
- **DNS resolution during a redial.** If the outbound address is ever a hostname, the fresh dial must resolve it — and if that resolution traverses the dead tunnel, the redial **hangs instead of failing fast** and no timer notices. Pin the outbound to an IP literal (as it is today), or ensure a direct DNS outbound with a hard timeout.
- **A Go panic is process death**, and every in-process change increases how often the risky paths execute. This is the real argument for keeping one process-restart tier alive as a genuine backstop.
- **The ordering constraint:** never probe within 500 ms of an `establish()`, and never treat a probe issued before the first post-establish network callback as authoritative.

---

## Report 1 — Senior Android / Doze expert

## 1. Current solution as implemented

**Processes.** `OnthecrowVpnService` runs in `:vpn` (`androidApp/src/main/AndroidManifest.xml:56-66`, `android:process=":vpn"`, `foregroundServiceType="specialUse"`). `OnthecrowVpnApplication.onCreate` builds the Koin graph **only in the main process** (`androidApp/.../OnthecrowVpnApplication.kt:17-19`); `:vpn` initializes just `AndroidXrayEnvironment` + `AndroidVpnEnvironment` in `OnthecrowVpnService.onCreate` (`OnthecrowVpnService.kt:104-113`).

**Connect.** `PlatformVpnController.connect` (`PlatformVpnController.android.kt:79-118`): sets `lastXrayJson`, publishes `Connecting`, polls `awaitVpnProcessGone` (3 s budget, 50 ms poll, `:144-158`), then `startForegroundService(ACTION_CONNECT)` with the xray JSON + split-tunnel lists. In `:vpn`, `onStartCommand` (`:115-152`) calls `startAsForeground()`, persists params to `ConnectionParamsStore`, and launches `runConnect` (`:175-220`): `applyUnderlyingNetworks(lastUnderlying)` (null at this point) → `stopTunnel()` → `Builder().establish()` → `startXrayOnTun` with a **dup** of the tun fd (`:227-250`) → broadcast `Connected` → `startMonitoring()`. Returns `START_STICKY`.

**Monitoring** (`:476-491`): resets `underlyingSeeded=false / lastUnderlying=null`, reads `PowerManager.isInteractive`, registers a `NOT_VPN + INTERNET` network callback (`registerBestMatchingNetworkCallback` on S+, main-looper handler, `:591-645`) and a runtime `SCREEN_ON/SCREEN_OFF` receiver (`:508-533`). Keepalive starts only if `screenOn`.

**Network change** (`:594-615`): `onAvailable` seeds `lastUnderlying` on the first callback and returns; on a subsequent different `Network` it calls `setUnderlyingNetworks(new)` and, **only if `screenOn`**, `startTunnelJob("network change", FORCE_RECOVER)`; if the screen is off it logs and defers. `onLost` (`:621-626`) only nulls `lastUnderlying`. `onLinkPropertiesChanged` (`:617-619`) only logs.

**Doze exit / screen on** (`:519-523`): `startTunnelJob("screen on", PROBE_FIRST)` → `probeTunnel(1500 ms)` (one UDP DNS A-query to 1.1.1.1:53 over the tun, `:462-474`) → `recover()` if it fails. `SCREEN_OFF` cancels `tunnelJob`.

**Keepalive** (`:434-450`): while screen-on, `delay(8_000)` then probe; 2 consecutive failures → `recover()`.

**Recovery** (`:405-413`): debounced on a persisted `SystemClock.elapsedRealtime()` stamp with `RECOVERY_KILL_DEBOUNCE_MS = 6_000`, then **`VpnStatusBroadcast.sendRecoverRequest(this)`** — a `sendBroadcast(Intent(ACTION_RECOVER).setPackage(...))` (`VpnStatusBroadcast.kt:45-48`) to a **runtime-registered** receiver in the main process (`:51-59`). The `:vpn` coroutine then falls through into `keepAliveLoop()` (`:390`).

**Main-process side** (`PlatformVpnController.android.kt:42-77`): `onRecoverRequested` → guard on `reconnecting` → guard on `lastXrayJson != null` → `reconnect(json)` = `disconnect()` → wait ≤4 s for `Disconnected|Error` → `connect(json)`. `disconnect()` sends `ACTION_DISCONNECT` via `startService`; `:vpn` `runDisconnect` (`:306-326`) clears the persisted params, broadcasts `Disconnecting`/`Disconnected`, `stopForeground(REMOVE)`, `stopSelf()`, and `Process.killProcess` after 300 ms (`:328-337`).

Constants in force: probe 1500 ms, keepalive 8000 ms × 2 fails, recovery debounce 6000 ms, process-death delay 300 ms, `awaitVpnProcessGone` 3000 ms, teardown wait 4000 ms. **No wakelock, no AlarmManager, no BOOT_COMPLETED, no `ACTION_DEVICE_IDLE_MODE_CHANGED`** anywhere in the tree (verified by grep over `core/` and `androidApp/`).

---

## 2. Problems

### P1 — The recovery request is sent to a process that is normally dead. This is the primary cause of "VPN never comes back."
`OnthecrowVpnService.kt:412` → `VpnStatusBroadcast.kt:45-48` → receiver registered at runtime in `PlatformVpnController.android.kt:42-44`. A **runtime-registered** receiver exists only while its process lives; a `sendBroadcast` targeting only runtime receivers **does not start the process**. There is no manifest receiver, no manifest component of any kind that can be woken.

**Verified in the field logs.** In `vpn-debug (13).log`: main process `26986` logs for the last time at `06-17 01:40:42.462` (line 26046); `:vpn` process `27094` keeps running and performs recoveries at `06-17 02:15:52`–`02:15:54` (lines 26057-26069); the next main process (`5982`) only appears at `06-17 02:16:00.032` (line 26070) because the **user launched the app**. The main process was dead for ~35 minutes while the tunnel was up. That is the normal steady state for a backgrounded VPN — the main process holds no foreground service and is a cached process, first in line for LMK and for OEM (HyperOS) cleanup.

Consequence: `recover()` records its debounce stamp (`:410`), sends a broadcast into the void, and returns. The tunnel stays dead until the user opens the app. **This alone explains both reported failures** (handover and Doze exit), because both funnel through `recover()`.

### P2 — Even when the main process is alive but cached, the broadcast is deferred by tens of seconds.
Same code path. Android defers broadcasts to cached/frozen processes (cached-app broadcast deferral, hardened by the app freezer on 14+/16).

**Verified.** `:vpn` sent `Connected` at `01:39:34.363`, `01:39:39.444`, `01:39:58.579`, `01:40:03.648`. All four were received by main at `01:40:42.460-462` (lines 26043-26046) — deferrals of **68 s, 63 s, 44 s, 39 s**, delivered as one batch. The `ACTION_RECOVER` broadcast travels the identical path. A recovery mechanism whose latency is 40-70 s (or ∞) cannot meet the reliability goal.

### P3 — Even if the broadcast arrives, `lastXrayJson` is almost always null in a freshly-restarted main process.
`PlatformVpnController.android.kt:52-56`: `lastXrayJson` is set only in `connect()` (`:80`), i.e. in-memory, per process instance. If the main process was killed and later restarted for any reason (WorkManager, Firebase, a content provider), the controller is reconstructed with `lastXrayJson == null` and the handler logs `"recover request ignored — no cached config"` and does nothing. The `:vpn` process has the config persisted (`ConnectionParamsStore`), the main process never reads it.

### P4 — The recovery path destroys the only fallback (persisted params) before it knows the reconnect will succeed.
`reconnect()` (`PlatformVpnController.android.kt:70`) calls `disconnect()`, which reaches `runDisconnect` → **`paramsStore.clear()`** (`OnthecrowVpnService.kt:311`). After that, `stopForeground(REMOVE)` + `stopSelf()` + `killProcess`. If the subsequent `connect()` fails for any reason (P5), there is no persisted config left, so the `onStartCommand(null)` self-heal branch (`:136-149`) takes the `"standing down"` path. The system is now permanently disconnected with no recovery trigger of any kind. Recovery must never share a code path with deliberate teardown.

### P5 — The reconnect issues a background FGS start after having just torn down the only FGS the UID had.
`PlatformVpnController.android.kt:106` `startForegroundService(...)`, executed from a `scope.launch` in a process with no visible activity, **after** `reconnect()` waited for `Disconnected` — i.e. after `stopForeground(REMOVE)` + `stopSelf()` already ran in `:vpn`. On Android 12+ that is a background FGS start from a UID that is no longer in `PROCESS_STATE_FOREGROUND_SERVICE` → `ForegroundServiceStartNotAllowedException`.

*Verified:* the ordering in code. *Inference:* the exception itself is not in the logs, because P1 means the path was never reached in the field. The one exemption the app may hold is the device-idle allowlist (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, requested at `MainActivity.kt:39,60-66`). From AOSP `ActiveServices`/`ActivityManagerService`, being on the device-idle allowlist yields an FGS-allowed reason (`REASON_DEVICE_IDLE` / `REASON_ALLOWLISTED_PACKAGE`) — so it *does* exempt in practice — but **this is not in the public exemption list and the dialog is user-declinable**. Relying on it is a coin flip per user. Note the app also does not declare `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, so the one *documented* background-FGS exemption available to it is unused.

### P6 — START_STICKY has never once restarted this service in the field.
Grep for `"sticky restart"` across **every** `vpn-debug*.log` returns **zero hits**. The self-heal branch at `OnthecrowVpnService.kt:136-149` has never executed. Combined with established fact #4 (`06-17 02:16:09.861` "killing :vpn process…" followed by nothing at all until the user acted at `02:16:30.071`), START_STICKY must be treated as non-existent for this service. *Inference on mechanism:* on 12+ a service restarted after process death is brought up without an FGS-start exemption, and a `specialUse` FGS that cannot legally call `startForeground()` is dropped rather than restarted.

### P7 — `lastUnderlying` goes stale during Doze and there is no longer any code to re-query it.
The working tree **deleted** `refreshUnderlyingFromSystem()` / `queryActiveUnderlying()` (visible in `git diff` of `OnthecrowVpnService.kt`, the block that used to run before each recovery attempt). Nothing replaces it.

**Verified that this matters:** `06-17 01:39:34.280` — `"underlying refresh: STALE 111 -> 101"`. The device went from Wi-Fi netId 111 to cellular netId 101 while the screen was off, and `onAvailable(101)` **never reached the service**; only the explicit re-query caught it. So `ConnectivityManager` callbacks are demonstrably not delivered (or are coalesced away) to this app during Doze. With the re-query removed, `setUnderlyingNetworks()` now stays pinned to a dead `Network` object indefinitely, and `recover()` has no chance of picking a correct upstream even if it worked.

### P8 — Deferring all recovery while the screen is off is the wrong policy for a VPN.
`OnthecrowVpnService.kt:609-613` (network change) and `:513-518` (screen off cancels `tunnelJob`). The tun stays up with a dead upstream, so **all** app traffic is black-holed — strictly worse than being disconnected. Every background app that wakes in a Doze maintenance window fails. And because the probe only runs after `SCREEN_ON`, the user's first 1.5 s (probe) + full recovery (several seconds, at best) after unlocking are dead. The battery argument does not hold: the expensive thing is the 8 s polling loop, not a single event-driven re-dial on an actual network change.

### P9 — Missing triggers.
- `ACTION_DEVICE_IDLE_MODE_CHANGED` (`PowerManager`) — the only reliable Doze enter/exit signal, delivered to runtime receivers, fires without the screen turning on. Completely absent.
- `onCapabilitiesChanged` / `NET_CAPABILITY_VALIDATED` — recovery fires on `onAvailable` (`:603-614`) regardless of validation. A cellular network typically appears unvalidated and validates 1-3 s later; recovering at `onAvailable` re-dials onto a not-yet-usable link, fails, and then waits ≥16 s for keepalive.
- `onLinkPropertiesChanged` (`:617-619`) — logged only. A route/address change on the *same* `Network` object (Wi-Fi reassociation, IPv6 prefix change, inter-RAT handover keeping the netId) produces **no** `onAvailable` and therefore **no** recovery at all.
- `onLost` (`:621-626`) — nulls `lastUnderlying` but does not call `setUnderlyingNetworks(null)`, so the VPN stays bound to a dead network until some `onAvailable` arrives.

### P10 — Recovery cadence after a missed request is 16-24 s.
`recover()` returns immediately (`:405-413`) and control falls to `keepAliveLoop()` (`:390`). The next attempt requires two consecutive 8 s-spaced probe failures. Combined with the 6 s debounce recorded *before* knowing whether anyone acted (`:410`), a lost recovery costs at least 16 s — repeatedly, forever, if P1 holds.

### P11 — No wakelock anywhere.
Verified by grep: the app never acquires a `PARTIAL_WAKE_LOCK`. `recover()` → broadcast → main process → `disconnect` → 4 s wait → `connect` → new process → `establish` + xray dial is a multi-second, multi-process sequence. If it is ever triggered with the screen off (which P8 currently prevents, but the correct design requires), the CPU can suspend mid-sequence and `delay()`/`withTimeoutOrNull` will not fire on time.

### P12 — Cancellation hazard in the recovery job.
`startTunnelJob` cancels the previous `tunnelJob` (`:372-373`). `onAvailable` runs on the main looper and can fire repeatedly during a handover (the logs show duplicate `onLost` pairs at `02:16:09.625/.705` and `02:18:09.866/.944`). Each fires a fresh `startTunnelJob(FORCE_RECOVER)` that cancels the previous one. With the current one-shot `recover()` this is mostly harmless, but any recovery implementation with work after the broadcast will be silently cancelled mid-flight.

**Ranking against the 100 % goal:** P1 ≫ P2 ≈ P6 > P4 ≈ P5 > P7 ≈ P3 > P9 ≈ P8 > P10 ≈ P11 ≈ P12.

---

## 3. How to fix it

### F1 — Recovery must be driven entirely inside `:vpn`. Never cross a process boundary with a broadcast.
Delete `sendRecoverRequest` / `registerRecoverRequest` (`VpnStatusBroadcast.kt:45-59`) and `onRecoverRequested`/`reconnect` (`PlatformVpnController.android.kt:47-77`). The main process is a UI mirror; it must never be on the critical path. It is dead ~most of the time (P1) and, when alive, deferred by ~a minute (P2).

The `:vpn` process is the one process guaranteed to exist while the tunnel exists, and while it holds a running FGS the **UID** is in `PROCESS_STATE_FOREGROUND_SERVICE` — which is the strongest and most certain FGS-start exemption on 12/13/14/16. Everything below exploits that.

### F2 — Make the process restart self-driven and legal, with an exact alarm as the restart engine.
Replace `recover()` (`OnthecrowVpnService.kt:405-413`) with, in order:

1. `PowerManager.PARTIAL_WAKE_LOCK` acquired with a timeout (~30 s) — held across the whole sequence (fixes P11).
2. **Re-write** the persisted `ConnectionParams` (never clear them here — fixes P4), plus a `recovery_in_progress` flag and attempt counter.
3. Schedule `AlarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, now + 1500 ms, pendingIntent)` for a **manifest-declared** `BroadcastReceiver` (new component; add `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` to the manifest). This alarm is scheduled *while the FGS is still alive*, so it is allowed even under App Standby buckets.
4. Only then `stopForeground(REMOVE)` is **not** called — instead `Process.killProcess(Process.myPid())` directly, so the hysteria pool dies with the process.

The manifest receiver is started by the alarm **into a fresh process** (manifest receivers do start processes — this is the whole point versus P1). An exact alarm firing puts the app on the **temporary power allowlist** for its duration, which is a *documented* exemption from the Android 12+ background-FGS-start restriction ("the app fires an exact alarm to complete a user-requested action"). From `onReceive` it loads `ConnectionParams` from disk and calls `startForegroundService(ACTION_CONNECT)`. Put the receiver in `:vpn` (`android:process=":vpn"`) so the restarted process is the one that will host the service, and mark it `android:exported="false"`, `android:directBootAware="false"`.

Why this is reliable across versions: manifest `BroadcastReceiver` + `setExactAndAllowWhileIdle` is the one wake mechanism that works in Doze on 6.0 through 16 (Doze rate-limits allow-while-idle alarms to ~1 per 9 min per app in deep Doze on older releases, ~1 per 10 min on 9+ — see the fallback note below). WorkManager/JobScheduler are **not** substitutes: jobs do not run in deep Doze and cannot start an FGS from background on 12+ without an expedited-job quota that a VPN cannot rely on.

**Failure mode & fallback:** the allow-while-idle rate limit means you cannot use this more than once per ~10 minutes during deep Doze. Mitigate with the alarm-clock variant only if a user-visible alarm is acceptable (it is not), so instead: (a) F3 removes most of the need to fire in deep Doze, and (b) keep the `onStartCommand(null)` self-heal as a second line, but only after F5 makes it reachable.

### F3 — Kill the pool without killing the process. This removes the whole fragile mechanism.
The process-kill design exists solely because xray-core's `manger` map survives `StopXray`. `local-libs/libxray/LibXray.aar` is a prebuilt binary but `scripts/build-libxray-android.sh` means you control the Go build. Two options, in order of preference:

- **Patch libXray** to export `ClearHysteriaPool()` (walk the package-global map, close each client, delete the entries) and call it from `PlatformXrayEngine.stop()` (`PlatformXrayEngine.android.kt:132-145`) right after `stopXray`. This turns recovery into an in-process `stopXray → clearPool → startXray` that takes ~200 ms, needs **no** process death, **no** FGS restart, **no** alarm, **no** main process — the FGS never stops, so no Android restriction applies at any point. This is the single highest-leverage change in this report and it makes P1/P2/P4/P5/P6/P11 all moot.
- If patching is off the table, verify the pool key: the brief states it is keyed by server *address*. If so, alternating the dial target between equivalent forms (hostname vs. resolved literal IP, or a second `:port` the server also listens on) forces a fresh pool entry. This is a hack and should be treated as a stopgap.

Recommendation: implement F3 as the real fix and F2 as the safety net for whatever the pool patch misses.

### F4 — Fix the triggers (in `registerUnderlyingNetworkCallback`, `OnthecrowVpnService.kt:591-645`).
- **Trigger on `onCapabilitiesChanged` with `NET_CAPABILITY_VALIDATED` becoming true on a network `!= lastUnderlying`**, not on bare `onAvailable`. Track `(network, validated)` and only recover once the new link is validated. Cuts the wasted first attempt described in P9.
- **Trigger on `onLinkPropertiesChanged`** when `lp.linkAddresses` / `lp.routes` change for the *current* underlying network. This is the only signal for a same-netId route change and is currently a pure no-op at `:617-619`.
- **On `onLost(lastUnderlying)`**, call `applyUnderlyingNetworks(null)` immediately so the VPN falls back to the system default rather than staying pinned to a dead handle.
- **Add an `ACTION_DEVICE_IDLE_MODE_CHANGED` receiver** alongside the screen receiver (`:508-533`). On exit-from-idle, run the probe-and-recover path *without* requiring `SCREEN_ON`. This is the correct Doze-exit trigger; `SCREEN_ON` is merely a proxy for it.
- **Restore `refreshUnderlyingFromSystem()`** (deleted in the working tree — P7) and call it at the top of every recovery and on every idle-mode exit. Scan `cm.allNetworks` for `NOT_VPN + INTERNET`, preferring `VALIDATED`; `activeNetwork` is useless here because we are the VPN. The log line `01:39:34.280 "underlying refresh: STALE 111 -> 101"` is the proof this is load-bearing.

### F5 — Recovery must never share a path with deliberate teardown.
Split `runDisconnect(stopService:Boolean)` (`:306-326`) into `runUserDisconnect()` (clears `paramsStore`, broadcasts, stops FGS, kills) and `runRecoveryRestart()` (keeps `paramsStore`, keeps `Connecting` status, kills). Only the user/revoke path may call `paramsStore.clear()` (`:311`). Likewise `sendStop`'s `lastXrayJson = null` (`PlatformVpnController.android.kt:128`) becomes irrelevant once F1 lands.

### F6 — Stop deferring on screen-off; change *what* is deferred instead.
- Keep cancelling the 8 s `keepAliveLoop` on `SCREEN_OFF` (that polling genuinely costs battery).
- **Always** act on a real network-change event, screen on or off (`:609-613`). It is one event-driven re-dial per handover — negligible battery, and it is what makes the tunnel alive the instant the user unlocks.
- Replace the screen-off silence with a **cheap, Doze-friendly heartbeat**: `setExactAndAllowWhileIdle` every ~10 min (the deep-Doze quota) that wakes `:vpn`, probes once, and recovers if dead. This bounds the worst-case dead-tunnel window at 10 minutes instead of "until the user unlocks."
- On `SCREEN_ON` / idle-exit, probe with a **shorter** timeout (400-600 ms, not 1500 ms — `PROBE_TIMEOUT_MS` at `:728`) and start the re-dial optimistically in parallel; a needless re-dial is cheap once F3 lands, whereas 1.5 s of dead traffic at unlock is user-visible.

### F7 — Cover the residual cases.
- **`RECEIVE_BOOT_COMPLETED` + a manifest receiver** (absent today): on boot, if `ConnectionParams` exist and the user had the tunnel up, restart it. Boot-completed is a documented FGS-background-start exemption path via the temporary allowlist on 12+; on 15+ verify `specialUse` is still permitted from `BOOT_COMPLETED` (see §4).
- **Always-on VPN + lockdown**: recommend it in-app and deep-link to `Settings.ACTION_VPN_SETTINGS`. When set, the system itself starts and restarts the `VpnService` and holds the app out of the standby restrictions — it is the closest thing to a hard guarantee on Android and it makes every mechanism above a fast-path rather than a necessity. It also changes the failure mode: with lockdown, a dead tunnel blocks traffic rather than leaking it.
- **Xiaomi/HyperOS**: add an in-app prompt for "Autostart" and "No battery restrictions" (`miui.intent.action.APP_PERM_EDITOR` / the standard `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` already at `MainActivity.kt:60-66`). HyperOS kills cached processes aggressively — which is exactly what the logs show happening to the main process — and no amount of app-side code beats it; the FGS in `:vpn` is what survives.
- **Fix P12**: give the recovery sequence its own `NonCancellable`-guarded critical section (or a dedicated single-permit actor) so a duplicate `onAvailable` cannot cancel an in-flight restart.

---

## 4. Open questions / things I could not verify

1. **No field log exists for the current build.** Grepping every `vpn-debug*.log` for `"requesting main-process reconnect"` and `"send recover request"` returns nothing. The broadcast-based design in the working tree has **never been observed running**. All conclusions about it (P1-P5) are from code reading plus logs of adjacent builds. The decisive experiment: reproduce a handover with the app swiped away, and `adb shell dumpsys activity processes | grep onthecrowvpn` to confirm the main process is absent when `recover()` fires.
2. **Whether the device-idle allowlist really exempts background FGS starts on this device/OEM.** My reading of AOSP says yes (`REASON_DEVICE_IDLE`), but it is not in the public exemption list and HyperOS may differ. Experiment: from a cached main process, call `startForegroundService` and catch `ForegroundServiceStartNotAllowedException`, with and without the battery-optimization exemption granted.
3. **Why exactly START_STICKY never restarts the service** (P6). I infer the restarted service cannot legally call `startForeground()`. `adb shell dumpsys activity services com.onthecrow.onthecrowvpn` immediately after a self-kill, plus `logcat -b all | grep -i "ActivityManager.*onthecrow"`, would show whether AMS scheduled a restart and what it refused.
4. **Whether `manger` is genuinely un-clearable without a process restart.** I could not inspect it — `local-libs/libxray/LibXray.aar` is a binary and the Go source is not vendored. F3's viability hinges on this. Experiment: build libXray from source with an added pool-clear export and measure the re-dial time after `stopXray → clearPool → startXray` on a changed network.
5. **Android 15/16 `specialUse` FGS restrictions.** I could not confirm whether API 35/36 added a runtime timeout or a `BOOT_COMPLETED` start restriction for `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (the 15 timeouts I am aware of are `dataSync`/`mediaProcessing`). Worth confirming before relying on F7's boot path. Also unverified: whether Google Play will accept `specialUse` with subtype `vpn` — AOSP has no VPN FGS type, so there is no alternative.
6. **Exact-alarm quota under deep Doze on this device** (F2/F6's heartbeat). The ~10 min allow-while-idle window is the documented AOSP figure; OEM tuning varies. Measure with `adb shell dumpsys alarm | grep onthecrow` over a long screen-off period.
7. **Whether the probe's own DNS traffic is being black-holed by something other than a dead tunnel** — e.g. the `com.google.android.gms` split-tunnel exclusion interacting with DNS. The `06-17 01:39:49`→`01:39:58` sequence (probe healthy at `01:39:39.484`, then two failures 10 s and 19 s later on a tunnel that had *just* been verified) is not fully explained by the stale-pool theory and deserves a packet-level look.

---

## Report 2 — Senior Go / libXray expert

I have everything I need. Writing the report.

---

# libXray / xray-core hysteria2 driver review — OnthecrowVPN Android

**Method note.** I found the full Go source tree for the vendored build at `/Users/onthecrow/Documents/Projects/OnthecrowVPN/.libxray-build/libXray` (libXray `v26.3.27`, per `scripts/build-libxray-android.sh:4`), and the resolved `xray-core v1.260327.0` + `apernet/quic-go v0.59.1-0.20260217092621` sources in the Go module cache. So almost everything below is **verified against source**, not inferred. Inferences are marked.

The single most important finding: **the hysteria pool is not the blocker, and the process-kill design is solving the wrong problem.** Details in §2.1.

---

## 1. Current solution as implemented

### 1.1 What libXray actually exposes and what each call does

| Call | Verified effect on xray-core state |
|---|---|
| `SetTunFd(fd)` | `xray_wrapper.go:15` → `xray/xray.go:51-53`: **only sets an environment variable** `xray.tun.fd` (`common/platform/platform.go:26`). Nothing else. It is read once, lazily, at `NewTun()` (`proxy/tun/tun_android.go:28`). The env var **persists for process lifetime**, so a start that forgets to re-set it silently reuses the previous fd *number*. |
| `RunXrayFromJSON` | `xray/xray.go:88-98` → `core.StartInstance("json", bytes)`. Creates **and starts** a new `*core.Instance` into the package global `coreServer` (`xray/xray.go:18`). Returns as soon as the instance is up — **it does not dial the server**. Confirmed by log: `xray start result: Success (81ms)` at `02:16:32.215`, while the first actual QUIC dial is at `xray (4).log:12` — triggered by the first proxied request. |
| `StopXray` | `xray/xray.go:106-115` → `coreServer.Close()`, `coreServer = nil`. Tears down inbounds/outbounds/goroutines of *that instance*. It does **not** touch package-level state in `transport/internet/*`. |
| `TestXray` | Loads + validates config only; does not run it. |
| `RegisterDialerController` / `RegisterListenerController` | `android_wrapper.go:24-32` → `controller/controller_android.go:13-17` → `internet.RegisterDialerController`. Appends a `control.Func` to a **process-global** controller slice on the default system dialer (`transport/internet/system_dialer.go:202-207`). Invoked as `lc.Control` inside `ListenPacket` (`system_dialer.go:66-79`) — i.e. **before bind**, on every new UDP socket. Correct placement. Note: registration is **append-only and global**; calling it repeatedly in one process accumulates duplicate controllers. |
| `convertShareLinksToXrayJson` | `share/parse_share.go` + `share/hysteria_mask.go`. Relevant below. |

### 1.2 The process-global hysteria pool — exact semantics

`transport/internet/hysteria/dialer.go`:

- **`var manger *clientManager` (`dialer.go:437`)** — package-level, initialised in `init()` (`dialer.go:473-484`). It is **not owned by the `core.Instance`**, so `StopXray` leaves it fully intact. Your premise here is **confirmed**.
- **Key = `dest.NetAddr()`** (`dialer.go:446`, used at `:450` and `:462`) — the literal `"host:port"` string of the outbound destination. Nothing else: not the outbound tag, not the credential, not the config.
- **Entries are never deleted.** `clientManager.clean()` (`dialer.go:428-435`) iterates the map and calls `client.clean()` (`dialer.go:332-339`), which calls `client.close()` **only if `status() == StatusInactive`**. `close()` (`dialer.go:141-147`) nils out `conn`/`pktConn`/`udpSM` but the map entry stays. The cleaner runs on a 30 s `task.Periodic` (`dialer.go:477-483`).
- **Crucially, `dial()` already self-heals** (`dialer.go:149-156`):
  ```go
  status := c.status()
  if status == StatusActive  { return nil }   // reuse
  if status == StatusInactive { c.close() }   // drop and fall through to a FRESH dial
  ```
  and `status()` (`dialer.go:129-139`) is nothing but `select { case <-c.conn.Context().Done(): StatusInactive; default: StatusActive }`.

So a pooled entry does **not** permanently pin a dead connection. It pins it exactly as long as quic-go takes to cancel the connection's context.

### 1.3 The QUIC parameters actually in force

`dialer.go:221-247` builds the `quic.Config`. With `streamSettings.QuicParams == nil`, `quicParams` is replaced by a zero-valued `&internet.QuicParams{}` (`dialer.go:158-161`), giving:

- `MaxIdleTimeout` → **30 s** (`dialer.go:245-247`, the `if == 0` default).
- `KeepAlivePeriod` → **0 = keepalive DISABLED**. The default is **commented out in the source** (`dialer.go:248-250`):
  ```go
  // if quicParams.KeepAlivePeriod == 0 {
  // 	quicConfig.KeepAlivePeriod = 10 * time.Second
  // }
  ```
  quic-go semantics confirmed: `interface.go:156-159` ("If set to 0, then no keep alive is sent") and `connection.go:862` (`if c.config.KeepAlivePeriod == 0 || ... { return }`).
- `DisablePathManager: true` (`dialer.go:231`) → **QUIC connection migration is explicitly disabled** for this transport (`quic-go/interface.go:183-185`: *"for hysteria2 port hopping, direct change remote address without connection migration logic"*).

**And `QuicParams` is nil for this user's config.** `share/hysteria_mask.go:10-36` only constructs `quicParams` when the share link carries `upmbps`/`downmbps`/`ports`; it *never* sets `MaxIdleTimeout` or `KeepAlivePeriod` under any circumstance. The field is under `streamSettings.finalmask.quicParams` (`infra/conf/transport_internet.go:1719-1723`, `:1734`). And `XrayConfigSanitizer.withTunInbound` (`core/xray/src/commonMain/kotlin/com/onthecrow/onthecrowvpn/xray/XrayConfigSanitizer.kt:51-110`) only injects the tun inbound and the log block — **it never touches `streamSettings`**. The field's JSON tags are `infra/conf/transport_internet.go:630-643`; validation bounds `maxIdleTimeout ∈ [4,120]`, `keepAlivePeriod ∈ [2,60]` at `:1951-1956`.

Log corroboration: the connect at `vpn-debug (13).log 02:16:32` produces `configBytes=1101` and `xray (4).log:17` reports `congestion bbr` — i.e. the `"brutal", ""` branch with `serverAuto == "auto"` (`dialer.go:303-310`), consistent with a bare `hysteria2://` link carrying no bandwidth params and therefore **no `finalmask` at all**.

### 1.4 Android control flow as implemented

- **Connect**: `PlatformVpnController.android.kt:79-118` → `awaitVpnProcessGone` (3 s, 50 ms poll, `:144-158`) → `startForegroundService(ACTION_CONNECT)`. In `:vpn`: `OnthecrowVpnService.kt:118-128` → `runConnect` (`:175-220`) → `Builder().establish()` (`:190-201`) → `startXrayOnTun` (`:227-250`): `master.dup().detachFd()` → `setTunFd(fd)` → `xrayEngine.start()`.
- **Network change**: `NOT_VPN|INTERNET` best-matching callback (`:591-645`). `onAvailable` with a different `Network` → `setUnderlyingNetworks` → if `screenOn`, `startTunnelJob(FORCE_RECOVER)` (`:609-613`); if screen off, deferred.
- **Doze exit**: `ACTION_SCREEN_ON` (`:519-523`) → `PROBE_FIRST` → `probeTunnel(1500 ms)` DNS-over-UDP to `1.1.1.1:53` (`:462-474`).
- **Keepalive**: `keepAliveLoop` (`:434-450`), probe every **8 s**, **2** consecutive failures → `recover`. Cancelled on `SCREEN_OFF` (`:513-518`).
- **Recovery**: `recover()` (`:405-413`) — debounced 6 s via `SharedPreferences` — sends a broadcast (`VpnStatusBroadcast.sendRecoverRequest`, `VpnStatusBroadcast.kt:45-48`) to the **main process**, which runs `disconnect() → await Disconnected (4 s cap) → connect()` (`PlatformVpnController.android.kt:68-77`). `runDisconnect` kills `:vpn` 300 ms later (`OnthecrowVpnService.kt:328-337`).
- Constants: `PROCESS_DEATH_DELAY_MS=300`, `RECOVERY_KILL_DEBOUNCE_MS=6000`, `PROBE_TIMEOUT_MS=1500`, `KEEPALIVE_INTERVAL_MS=8000`, `KEEPALIVE_FAILS_BEFORE_RECOVER=2` (`:718-734`).
- Manifest (`androidApp/src/main/AndroidManifest.xml`): `foregroundServiceType="specialUse"` + subtype `vpn` (`:51`, `:58-60`), `android:process=":vpn"` (`:53`). **No `WAKE_LOCK`, no `RECEIVE_BOOT_COMPLETED`, no `SCHEDULE_EXACT_ALARM`.**

---

## 2. Problems

Ranked by threat to the ~100 % goal.

---

### **P1 — The ~28–30 s stall is quic-go's `MaxIdleTimeout`, not pool eviction. Fixable in config; the entire process-kill architecture is built on a misdiagnosis.** *(Critical)*

**Where:** `dialer.go:245-247` (default 30 s) and `dialer.go:248-250` (keepalive default commented out), combined with `share/hysteria_mask.go:10-36` and `XrayConfigSanitizer.kt:51-110` never emitting `finalmask.quicParams`.

**Mechanism (fully verified):**
1. Wi-Fi→cell handover. The protected UDP socket (`system_dialer.go:53-80`, bound `0.0.0.0:0` on the old link) is now black-holed.
2. `c.conn.Context()` is **not** Done — quic-go only cancels it when the idle timer fires. With `KeepAlivePeriod == 0` there are no pings, so `c.idleTimeout` counts down from the last *received* packet (`quic-go/connection.go:2428-2433`: `idleTimeout = min(local, peer)`; `keepAliveInterval = min(KeepAlivePeriod, idleTimeout/2)`).
3. Therefore for **30 s**, `status() == StatusActive` (`dialer.go:129-139`), `dial()` returns `nil` at `dialer.go:151` without dialing, and every `c.tcp()` / `c.udp()` opens a stream on a corpse.
4. Only after 30 s does the context cancel, `status()` flip to `StatusInactive`, and the very next `dial()` call `c.close()` + re-dial (`dialer.go:154-155`).

**Evidence:** `xray (4).log:12,17` shows exactly one `dialing to udp:78.17.84.51:1935` (21:16:32.575) → `congestion bbr` (21:16:32.764) for the whole session — a fresh dial costs **~190 ms**, not 30 s. Established fact #3 ("no fresh dial for ~28 s") is precisely the 30 s idle timer, off by the elapsed time since the last inbound packet.

**Consequence:** the premise in `OnthecrowVpnService.kt:169-174` and `:394-399` — *"an in-process xray restart reuses the stale pooled connection and takes ~30 s"* — is **true as observed but wrong as diagnosed**. It is not the pool that is stale; it is that nothing has told quic-go the connection is dead. The process kill works only because it happens to bypass the timer. Setting `maxIdleTimeout: 5` + `keepAlivePeriod: 2` makes the pool self-heal in ~5 s **with no process kill, no service restart, and no cross-process broadcast**.

---

### **P2 — Recovery depends on the main process being alive and holding in-memory state. After Doze it very often is not.** *(Critical — this is the Doze-exit failure)*

**Where:** `OnthecrowVpnService.kt:412` → `VpnStatusBroadcast.kt:45-48` → `PlatformVpnController.android.kt:42-44, 47-62`.

**Mechanism:** `registerRecoverRequest` uses `Context.registerReceiver` — a **runtime-registered** receiver. Runtime receivers exist only while the process lives; they are not in the manifest, so the broadcast **cannot start the main process**. `:vpn` is a foreground service and survives Doze; the main process is a plain backgrounded app and is a prime LMK/Doze eviction candidate (aggressively so on HyperOS). When it has been killed:
- the broadcast is delivered to nobody;
- `recover()` has already burned its 6 s debounce token (`:410` before `:412`), and worse, `recordRecoveryKill` persists to `SharedPreferences`, so the tunnel stays dead until the *next* keepalive pair (≥16 s) and then fails again identically, forever.

Even if the main process **is** alive, `lastXrayJson` is a `@Volatile` in-memory field (`:27-28`). Any main-process restart between connect and recovery leaves it `null` → `"recover request ignored — no cached config"` (`:53-56`). The `:vpn` process *has* the config persisted (`ConnectionParamsStore`, `OnthecrowVpnService.kt:102, 125`) — the process that can't act holds the data; the process that acts doesn't.

**Inference (marked):** I cannot prove main-process death from log 13 (its main process, pid 5982, stays alive throughout). This is read from the architecture. The experiment in §4.1 settles it.

**Secondary:** `sendStop()` sets `lastXrayJson = null` (`:128`) but `reconnect()` captured `json` first (`:68`) — correct. However `reconnecting` (`:31`) is a non-atomic `Boolean` set from a receiver thread and cleared from a coroutine (`:57, 60`); two recover broadcasts can both pass the `:48` check. Minor, but it can double-fire disconnect→connect.

---

### **P3 — On network change, recovery is requested but the tunnel is never repaired in-process; the whole repair is outsourced across a process boundary that can silently drop it.** *(Critical — this is the Wi-Fi↔cell failure)*

**Where:** `OnthecrowVpnService.kt:379` (`FORCE_RECOVER → recover(reason)`), `:405-413`.

**Evidence (log 13, previous revision but the same failure shape):**
```
02:16:09.861 [vpn/6240] recover (network change): killing :vpn process ...
   ← 20 SECONDS OF NOTHING —
02:16:30.071 [main/5982] sendStop: ...DISCONNECT   ← the USER pressed the button
```
and again at `02:18:10.059`, where `xray (4).log` simply ends. Established fact #4.

The current revision replaced self-kill with a broadcast, which removes the START_STICKY dependency but replaces it with the P2 dependency. In both revisions, **`:vpn` itself does nothing to fix the tunnel** — it has the tun fd, the config, the network callback and the xray instance, and it delegates. `recover()` is not even `suspend`; it fires and returns, then `keepAliveLoop()` runs on a tunnel nobody is repairing.

**Additionally:** `onLost` sets `lastUnderlying = null` (`:621-626`). A Wi-Fi flap (lost → same network back) then reads as `null -> 118`, i.e. a "change", triggering a full recovery for a network that never actually changed. Visible at `02:16:09.625/.705` (two `onLost: 111`) → `02:16:09.796 underlying changed: null -> 101`.

---

### **P4 — The screen-off gate makes Doze recovery structurally impossible, and the wake path has no wakelock.** *(High)*

**Where:** `OnthecrowVpnService.kt:513-518` (SCREEN_OFF cancels `tunnelJob`), `:609-613` (network change ignored while screen off), `:486-490`.

While the screen is off — exactly when Doze kills the QUIC connection — there is **zero** health machinery. Recovery is deferred to `SCREEN_ON` (`:519-523`), which then does `PROBE_FIRST` with a 1500 ms budget. So on every unlock the user eats: probe timeout (1.5 s) → broadcast → main-process wake → `disconnect()` → `awaitVpnProcessGone` (up to 3 s) → status await (up to 4 s) → new process → establish → **plus** a first-packet dial. Best case ~1 s, worst case ~9 s of dead network in the foreground, and that is the *success* path.

There is **no `WAKE_LOCK` permission** in the manifest, so between `SCREEN_ON` and the reconnect completing, the CPU can be suspended mid-sequence. The battery-optimization exemption (fact #7) exempts the app from Doze *network/job* restrictions; it does **not** hold the CPU awake.

**Note:** P1 largely dissolves this. With `keepAlivePeriod: 2`, the connection dies during Doze quickly and cleanly, and the first packet after wake re-dials in ~190 ms with no coordination at all.

---

### **P5 — The pool caches the whole client config, not just the connection, keyed only by `host:port`.** *(Medium — silent wrong-credential bug)*

**Where:** `dialer.go:449-465`. On a pool **hit**, only `c.setCtx(ctx)` runs (`:464`). `c.config` (auth), `c.tlsConfig`, `c.socketConfig`, `c.quicParams` are captured once at `:453-461` and **never refreshed**.

So in-process: switching to a different hysteria2 config that happens to share `host:port` (a re-issued credential, a different user on the same node) reuses the **old auth string** — and if the old QUIC conn is still `StatusActive`, it never even re-authenticates. This is currently masked by the process kill, and it becomes a live bug the moment you move to in-process reconnects. Any in-process design must account for it (see §3.4).

Also `manger.m` grows unbounded — entries are never deleted (`dialer.go:428-435`). Bounded by distinct server addresses, so a leak but not a serious one.

---

### **P6 — `stopXray()` closes a tun fd that Go's gVisor endpoint may still be reading.** *(Medium — latent, becomes serious with in-process restart)*

**Where:** `OnthecrowVpnService.kt:299-304`; `proxy/tun/tun_android.go:24-49`.

`AndroidTun.Close()` is **a no-op — it does not close `t.tunFd`** (`tun_android.go:48-50`). `NewTun` wraps the fd in a gVisor `fdbased` endpoint (`:52-58`) with reader goroutines. Kotlin then closes the fd from underneath it via `ParcelFileDescriptor.adoptFd(fd).close()` (`:302`), immediately after `xrayEngine.stop()` returns. `core.Instance.Close()` does not join those goroutines. The fd number is then free for reuse — the next `establish()` or `dup()` can be handed the same number while a stale Go reader still holds it.

Log 13 shows this fd churn plainly: `tun established: fd=102` / `setTunFd(101)` at `02:16:02.710-.712`, and the identical pair `102`/`101` at `02:16:32.109-.110`. The numbers recycle every cycle.

Also: `NewTun` calls `unix.SetNonblock(fd, true)` (`tun_android.go:33`). `O_NONBLOCK` is a property of the **open file description**, which `dup()` shares — so this also flips the master `tunInterface` non-blocking. Harmless today (the app never reads it directly), but worth knowing.

---

### **P7 — QUIC connection migration is disabled, and could not help anyway.** *(Informational, but it closes a design avenue)*

`dialer.go:231` sets `DisablePathManager: true` unconditionally, and it is not exposed through `QuicParamsConfig` (`infra/conf/transport_internet.go:630-643`) — **you cannot turn it on from JSON in this build.**

It would not help regardless: the protected socket is created via `lc.ListenPacket` on `0.0.0.0:0` with `protect(fd)` applied in `lc.Control` (`system_dialer.go:66-80`). `VpnService.protect()` marks the socket to bypass the tun; it does **not** rebind it when the default network changes. After a handover the kernel's source-address selection for that socket is stale. Client-side QUIC migration needs a *new* local socket path, which nothing here provides. Fresh-dial is the correct and only strategy.

---

### **P8 — `runConnect` starts xray before the underlying network is known.** *(Low)*

`OnthecrowVpnService.kt:185` calls `applyUnderlyingNetworks(lastUnderlying)` with `lastUnderlying == null` on a fresh process (log: `02:16:32.005 runConnect: ... underlying=null` → `setUnderlyingNetworks(default)`), and `startMonitoring()` (which registers the callback) runs only **after** xray starts (`:203-207`). The callback then lands at `02:16:32.396`, 255 ms later. Benign today only because hysteria dials lazily on first traffic (`dialer.go:172` is reached from `c.tcp()`/`c.udp()`, not from instance start). It becomes a real race the moment anything dials eagerly.

---

### **P9 — `registerProtectControllers` re-registers on every start.** *(Low)*

`PlatformXrayEngine.android.kt:103` calls it inside `start()`. `internet.RegisterDialerController` (`system_dialer.go:202-207`) **appends** to a global slice iterated at `system_dialer.go:67`. In the current kill-per-reconnect design each process registers once, so this is invisible. Under in-process restarts, controllers accumulate and every socket gets `protect()`ed N times. Harmless functionally, but it must be made idempotent before §3.2 ships.

---

## 3. How to fix it

### 3.1 — Inject `finalmask.quicParams` with an aggressive idle timeout and an explicit keepalive *(fixes P1; highest value/effort ratio in this report)*

**Where:** `core/xray/src/commonMain/kotlin/com/onthecrow/onthecrowvpn/xray/XrayConfigSanitizer.kt` — extend `withTunInbound` (or add a `withQuicParams` step applied at `OnthecrowVpnService.kt:235`) to merge into every outbound with `streamSettings.network == "hysteria"`:

```jsonc
"streamSettings": {
  "finalmask": {
    "quicParams": {
      "maxIdleTimeout": 5,     // seconds; validated range [4,120]
      "keepAlivePeriod": 2     // seconds; validated range [2,60]
      // preserve any congestion / brutalUp / brutalDown / udpHop already present
    }
  }
}
```

**Field names are verified**, not guessed: `infra/conf/transport_internet.go:630-643` (JSON tags), `:1719-1723` (`FinalMask` under `"finalmask"`), `:1951-1956` (bounds), `:1966-1983` (mapping into `internet.QuicParams`), consumed at `dialer.go:222-247`.

**Merge, don't overwrite.** `share/hysteria_mask.go:12` only allocates `quicParams` when the link carries bandwidth/ports; if it exists, keep `congestion`/`brutalUp`/`brutalDown`/`udpHop` intact — dropping them silently downgrades brutal → BBR.

**Mechanism:**
- `keepAlivePeriod: 2` → `keepAliveInterval = min(2s, idleTimeout/2 = 2.5s) = 2s` (`quic-go/connection.go:2433`); pings resume at `connection.go:862`.
- `maxIdleTimeout: 5` → `idleTimeout = min(5s, peer)` (`connection.go:2428-2431`); we can lower unilaterally regardless of server config.
- After a handover or Doze, the connection's context cancels within ~5 s. `status()` → `StatusInactive` → `dial()` closes and re-dials (`dialer.go:154-155`). Measured fresh-dial cost: **~190 ms** (`xray (4).log:12→17`).

**Trade-offs:** a ~2 s ping in each direction (~30 packets/min, well under 2 KB/min) — real but small, and it is the same traffic your 8 s probe already generates. A >5 s connectivity gap on a bad cell tears down a healthy connection; the re-dial costs one RTT + the HTTP/3 auth round-trip, so this is a good trade. If it proves too twitchy in the field, `maxIdleTimeout: 8` / `keepAlivePeriod: 3` is the conservative dial-back; anything ≥ 15 s reintroduces user-visible stalls.

**Fallback if the server rejects it:** it cannot — `MaxIdleTimeout` is a locally-chosen transport parameter and the effective value is `min()` of the two. If a server advertises something lower, it already wins today.

---

### 3.2 — Move recovery back into `:vpn`, in-process *(fixes P2, P3, and most of P4)*

Once §3.1 lands, the process kill is no longer needed to clear the pool, because the pool clears itself. Replace `recover()` (`OnthecrowVpnService.kt:405-413`) with a local, `suspend`, mutex-guarded ladder — **no broadcast, no main process, no `START_STICKY`**:

1. **Tier 0 (~0 ms, most cases):** on `FORCE_RECOVER`, `setUnderlyingNetworks(newNetwork)` (already done at `:606`) and then simply **wait and re-probe**. Within ~5 s the pooled conn is `StatusInactive` and the next probe packet triggers a fresh protected dial on the new link. Probe every 1 s for 8 s.
2. **Tier 1 (if Tier 0 fails):** `stopXray()` → `setTunFd(dup)` → `xrayEngine.start()`, **keeping `tunInterface` open** (the code already does this correctly — `:222-231`, `:339-346`). Must fix P6 and P9 first.
3. **Tier 2 (if Tier 1 fails twice):** full rebuild — close and re-`establish()` the tun, then Tier 1.
4. **Tier 3 (last resort only):** the existing cross-process disconnect→connect.

Keep the main-process broadcast path as Tier 3 only, and make it survivable: register the recover receiver **in the manifest** (`androidApp/src/main/AndroidManifest.xml`) with an explicit `<intent-filter>` so it can cold-start the main process, and **read the config from `ConnectionParamsStore`** in `onRecoverRequested` (`PlatformVpnController.android.kt:52-56`) instead of trusting `lastXrayJson`. Also move `recordRecoveryKill()` (`OnthecrowVpnService.kt:410`) to *after* a confirmed successful recovery, so a dropped request doesn't burn the debounce.

**Reliability on modern Android:** everything in Tiers 0–2 happens inside a process that is already a running foreground service holding the tun fd. It touches no FGS background-start restriction (Android 12+), no BAL, no `START_STICKY`, no broadcast delivery, no `runningAppProcesses` polling. It is the only tier that is architecturally immune to the failures in P2.

---

### 3.3 — Run the health machinery while the screen is off, with a wakelock *(fixes the rest of P4)*

- Add `<uses-permission android:name="android.permission.WAKE_LOCK" />` and hold a `PARTIAL_WAKE_LOCK` **for the duration of a recovery attempt only** (acquire at the top of the tier ladder, release in a `finally`, with a hard timeout of ~20 s). Without it the CPU can suspend between `SCREEN_ON` and the reconnect completing.
- Do not cancel `tunnelJob` on `SCREEN_OFF` (`OnthecrowVpnService.kt:513-518`). Instead **slow it down**: keep the 8 s cadence while screen-on, drop to ~60 s while screen-off. With `keepAlivePeriod: 2` the QUIC layer is already keeping itself honest; the probe is only there to *notice*. A 60 s probe in Doze is cheap and it makes "VPN was dead the whole night" impossible.
- Stop gating network-change recovery on `screenOn` (`:609-613`). A handover while the screen is off is exactly the case that must be handled — and post-§3.1 it costs a single re-dial.
- **Doze caveat:** even with the battery-optimization exemption (fact #7), deep Doze parks the radio between maintenance windows, so the 2 s keepalive will not actually reach the wire continuously. That is fine and in fact desirable: the connection dies, `status()` goes `StatusInactive`, and the first real packet after wake re-dials immediately. The failure mode we are eliminating is *dead but believed alive*, not *dead*.

---

### 3.4 — Guard the pool's config-capture bug before relying on in-process restarts *(P5)*

`dialer.go:449-465` reuses `c.config` (auth) on a key hit. In-process restarts therefore **must not** be used to switch to a different config on the same `host:port`. Two options:

- **Simple:** keep the process kill for *user-initiated config switches only* (rare, latency-tolerant), and use in-process recovery for network-change/Doze (frequent, latency-critical). This needs no upstream change and is my recommendation.
- **Key rotation (no patch, works today):** the pool key is exactly `dest.NetAddr()`. If the server listens on several ports, alternating the port across reconnects — or setting `udpHop.ports` — mints a **new pool entry** with freshly captured config, bypassing both P5 and any residual `StatusActive` staleness instantly. Alternating between a hostname and an IP literal also changes the key string. Cost: the old entry lingers holding a socket until the 30 s cleaner (`dialer.go:477`) reaps it.

**On flushing the pool in-process:** I checked, and the answer is **no, not without patching xray-core.** `manger` is unexported, `clientManager.clean()` is unexported, and the only exported symbols in the whole `transport/internet/hysteria` package are `Dial` and `Listen` (verified by `grep -E "^func [A-Z]"`). There is no config knob, no outbound tag, and no dialer-controller trick that reaches it — the key is `dest.NetAddr()` and nothing else. `StopXray` provably cannot touch it (`xray/xray.go:106-115` only closes the `core.Instance`).

If you ever want a true flush, it is a genuinely small patch: add `func CloseAll() { manger.mutex.Lock(); for k, c := range manger.m { c.close(); delete(manger.m, k) }; manger.mutex.Unlock() }` to `dialer.go`, expose it through a libXray wrapper, and add a `replace github.com/xtls/xray-core => <fork>` to `.libxray-build/libXray/go.mod`. The repo already builds xray from source (`scripts/build-libxray-android.sh`) and already carries a custom package (`.libxray-build/libXray/onthecrow_convert/`), so the machinery exists. **But §3.1 makes it unnecessary** — I would not take on a fork for this.

---

### 3.5 — Fix the tun-fd handoff *(P6)*

In `OnthecrowVpnService.kt:299-304`, do not close the dup'd fd immediately. `AndroidTun.Close()` is a no-op (`tun_android.go:48-50`) and xray's gVisor readers are not joined by `Instance.Close()`. Either:

- **(a)** never close the dup — leak one fd per xray restart and let process death reclaim it (bounded, and safe); or
- **(b)** delay the close by ~200 ms after `stopXray()` returns and, critically, **always call `setTunFd()` with a fresh dup before every `start()`** (already correct at `:229-231` — preserve that invariant, because the env var persists for the process lifetime per `xray/xray.go:51-53`).

I'd take (a): an fd is cheap, a recycled fd number under a live reader is a heisenbug.

---

### 3.6 — Small correctness fixes

- `OnthecrowVpnService.kt:621-626`: do not null `lastUnderlying` on `onLost`. Track the last *available* network and compare `netId`s, so a Wi-Fi flap (`02:16:09.625/.705`) doesn't read as a change (P3 tail).
- `PlatformXrayEngine.android.kt:103`: make `registerProtectControllers` idempotent — a process-level `AtomicBoolean` guard (P9).
- `PlatformVpnController.android.kt:31, 48, 57`: replace `reconnecting: Boolean` with an `AtomicBoolean.compareAndSet` (P2 tail).
- `OnthecrowVpnService.kt:185, 203-207`: call `registerUnderlyingNetworkCallback()` *before* `startXrayOnTun`, so `setUnderlyingNetworks` is correct before any dial can happen (P8).

---

## 4. Open questions / things I could not verify

1. **Is the main process actually dead at recovery time?** This is the load-bearing assumption behind P2 and I could not confirm it from log 13 (pid 5982 survives throughout, and that log predates the broadcast-based revision). **Experiment:** after connecting, run `adb shell am kill com.onthecrow.onthecrowvpn.dev` (kills the main process, leaves the FGS `:vpn`), then toggle Wi-Fi. If `:vpn` logs `send recover request` with no matching `CTRL` line, P2 is confirmed as the Doze failure mode. Takes two minutes and would settle the single most consequential question here.

2. **Is `finalmask.quicParams` actually reaching the built config today?** I inferred it is absent from `share/hysteria_mask.go:12` plus the bare `hysteria2://` link plus `configBytes=1101`, but I never saw the generated JSON. **Experiment:** log the full `runtimeJson` once at `OnthecrowVpnService.kt:245` (it's 1101 bytes) and confirm there is no `finalmask` key. If one is present, §3.1 becomes a merge rather than an insert — the code must handle both.

3. **The exact wall-clock between handover and stall-end.** Fact #3 says "~28 s" and I attribute it to the 30 s idle timer, but that requires knowing the time since the last *received* packet. The current logs don't show a completed 30 s recovery for me to measure. **Experiment:** set `maxIdleTimeout: 5` and re-run the Wi-Fi→cell test with xray at `debug`. If the second `dialing to udp:...` appears ~5 s after the handover instead of ~28 s, P1 is proven outright and §3.2 is unblocked.

4. **Server-side idle timeout.** If the hysteria2 server advertises a `max_idle_timeout` below 5 s, my proposed value is a no-op (`min()` wins, `connection.go:2430-2431`). Unlikely — hysteria2 defaults to 30 s — but I have no visibility into that server.

5. **Whether the battery-optimization exemption is actually granted on the test device.** Fact #7 says it's *requested*. If it was declined, the Tier-3 `startForegroundService` from a background broadcast (`PlatformVpnController.android.kt:105-109`) will throw `ForegroundServiceStartNotAllowedException` on Android 12+. **Check:** `adb shell dumpsys deviceidle whitelist | grep onthecrow`. §3.2's Tiers 0–2 are immune either way, which is a further argument for landing them.

6. **HyperOS-specific process management.** I have no device logs showing OEM kills, and nothing in the repo compensates for them (no `RECEIVE_BOOT_COMPLETED`, no autostart guidance). The `:vpn` FGS should survive, but Xiaomi's "autostart" restriction is known to break exactly the P2 broadcast path. Untested here.

---

## Report 3 — Senior systems / network expert

## 1. Current solution as implemented

*(Everything below is read from the working tree, not from the colleagues' summaries. Line numbers are current.)*

**Tun construction.** `OnthecrowVpnService.kt:190-201`:

```
Builder().setSession(...).setMtu(1500)
  .addAddress("10.77.0.2", 32)
  .addRoute("0.0.0.0", 0)
  .addDnsServer("1.1.1.1")
  .apply { applySplitTunnel(this) }
  .establish()
```

IPv4 only. No `addAddress` for IPv6, no `::/0` route, no IPv6 DNS server. MTU 1500. Split tunnel is `addDisallowedApplication` for `com.google.android.gms` / `gsf` (log `01:39:34.287`). The service's own package is deliberately left inside the tunnel (`:196-198`).

**Tun fd handoff.** `startXrayOnTun` (`:227-250`): `tunInterface.dup().detachFd()` → `xrayEngine.setTunFd(fd)` → `xrayEngine.start(runtimeJson)`. On the Go side `setTunFd` writes only the process-global env var `xray.tun.fd` (`platform.TunFdKey`), read lazily by `proxy/tun/tun_android.go:28-40`, which does `unix.SetNonblock(fd, true)` and wraps the fd in a gVisor `fdbased` endpoint (`tun_android.go:52-58`). `AndroidTun.Close()` is **a literal no-op** (`tun_android.go:47-49`) — verified in the module cache at `xray-core@v1.260327.0`.

`stopXray` (`:299-304`) calls `xrayEngine.stop()` then `ParcelFileDescriptor.adoptFd(dupFd).close()`.

**Despite the class comment at `:60-63` ("kept OPEN for the whole session … we never rebuild it on a network change"), the tun is torn down and rebuilt on every recovery.** `runConnect` calls `stopTunnel()` at `:188`, and `stopTunnel` (`:340-346`) closes the master `tunInterface`. There is no `keepTun` path left in the tree — `git diff` shows it was deleted.

**Upstream sockets.** `protectSocket` (`:565-571`) is invoked via the libXray `DialerController` proxy (`PlatformXrayEngine.android.kt:169-187`, `ProtectFdInvocationHandler`), which xray calls from `lc.Control` inside `DefaultSystemDialer.Dial` (`transport/internet/system_dialer.go:66-79`) — i.e. **before bind, on a fresh socket**. Placement is correct. The upstream is `lc.ListenPacket(ctx, "udp", "0.0.0.0:0")` (`system_dialer.go:80-88`) wrapped in `PacketConnWrapper{Dest: destAddr}` — an **unconnected** UDP socket with a fixed local port and a wildcard local address. `Network.bindSocket()` is not called anywhere (removed).

**`setUnderlyingNetworks`.** `applyUnderlyingNetworks` (`:578-581`) is called from `runConnect:185` (always with `lastUnderlying == null` on a fresh process — verified `02:16:32.005 runConnect: … underlying=null`), and from `onAvailable` (`:599`, `:606`). The network callback is registered only *after* xray has started (`:203-207` → `startMonitoring():483`); the first seed arrives ~390 ms later (`02:16:32.109` establish → `02:16:32.396` seed).

**Health probe.** `probeTunnel` (`:462-474`): a bare `java.net.DatagramSocket()` — **unconnected** — `send()`s a DNS A-query to `1.1.1.1:53` and `receive()`s into a 512-byte buffer. Success = `response.length > 0`. No `connect()`, no transaction-ID check, no source-address check, no local-address check. Timeout 1500 ms (`:728`).

**Recovery.** `recover()` (`:405-413`) → 6 s persisted debounce → `VpnStatusBroadcast.sendRecoverRequest` (`VpnStatusBroadcast.kt:45-48`) → main process `reconnect()` (`PlatformVpnController.android.kt:68-77`) → `disconnect()` → `runDisconnect` → `paramsStore.clear()`, `stopForeground(REMOVE)`, `stopSelf()`, `Process.killProcess` after 300 ms (`:328-337`) → `connect()` → new `:vpn` process → new tun → new xray.

**Constants:** MTU 1500, probe 1500 ms, keepalive 8000 ms × 2, recovery debounce 6000 ms, process-death delay 300 ms, `awaitVpnProcessGone` 3000 ms, teardown wait 4000 ms.

**QUIC parameters actually in force** (verified in `transport/internet/hysteria/dialer.go` at `xray-core@v1.260327.0`, since `XrayConfigSanitizer` never emits `streamSettings.finalmask.quicParams`): `MaxIdleTimeout = 30 s` (`dialer.go:245-247`), `KeepAlivePeriod = 0` — **keepalive disabled**, the default is commented out (`dialer.go:248-250`), `DisablePathManager: true` (`dialer.go:231`), unconditional and not exposed in JSON. Pool `manger` keyed on `dest.NetAddr()`, entries never deleted (`dialer.go:428-435, 437, 448-465`).

---

## 2. Problems

### P1 — The health probe returns false-positive "healthy" verdicts by escaping the tunnel entirely, and it does so *specifically in the window recovery runs in*. This is the reason recovery stops one step short of working. **(Critical, and neither colleague report found it)**

**Where:** `OnthecrowVpnService.kt:462-474`, in combination with the tun rebuild at `:188`/`:340-346`.

**Evidence — this is proven, not inferred.** Every genuine probe appears in xray's tun reader log as `proxy/tun: processing from udp:10.77.0.2:<port> to udp:1.1.1.1:53`. Correlating `vpn-debug (13).log` with `xray (3).log` (xray runs 5 h behind):

| app time | app verdict | matching xray tun entry |
|---|---|---|
| 01:39:32.774 | probe (screen-on check) | **20:39:32.774** ✓ |
| 01:39:34.376 | probe, failed | **20:39:34.376** ✓ |
| 01:39:36.382 | probe, failed | **20:39:36.382** ✓ |
| **01:39:39.484** | **"tunnel probe OK after 39ms"** | **absent** ✗ |
| 01:39:49.003 / 47.5 / 48.5 | keepalive fail | 20:39:47.505, 48.589 ✓ |
| 01:39:58.584 | probe, failed | 20:39:58.584 ✓ |
| **01:40:03.687** | **"tunnel probe OK after 38ms"** | **absent** ✗ (nearest entries 20:40:03.426 and 20:40:11.691) |

Both false positives were sent **~33 ms after `Builder.establish()` returned** (establish `01:39:39.411`, probe sent `.445`; establish `01:40:03.616`, probe sent `.649`). Every probe that *did* traverse the tun was sent ≥ 70 ms after establish, or on a settled tun. The correlation is perfect across the whole log.

**Mechanism (inference, but with a narrow candidate set).** `VpnService.establish()` returns the tun fd as soon as `Vpn.establish()` has created the interface, but the framework work that makes it *routable* — registering the VPN `NetworkAgent` with ConnectivityService, installing the per-UID `ip rule` entries and the VPN routing table in netd, and plumbing DNS — completes asynchronously afterwards. A datagram sent from our own UID inside that window resolves to the **physical default network** and leaves in the clear, gets a legitimate answer from 1.1.1.1, and the probe reports success. The runner-up explanation (the recycled-fd race in P3 stealing the packet) is ruled out because it would produce a timeout, not a 39 ms success.

**Consequence, and this is the whole story of the two reported failures:** at `01:39:39.484` the recovery loop concluded "tunnel healthy — done" on a tunnel that was in fact still dead (xray had just reused the stale pooled QUIC connection — see P2). 9.5 s later the keepalive failed again (`01:39:49.003`), and the identical false-success/real-failure cycle repeated at `01:39:58` → `01:40:03`. The recovery machinery was working; **the sensor was lying**, in exactly the state it exists to detect.

Two secondary defects in the same 12 lines: the socket is never `connect()`ed, so the kernel accepts a datagram from **any** source (an unrelated stray UDP packet counts as success); and the DNS transaction ID (`PROBE_DNS_TXID`) is built into the query and then never checked in the response.

### P2 — The ~30 s stall is quic-go's `MaxIdleTimeout`, and it is counted **from CPU resume, not from the network event**, because Go's monotonic clock freezes across Android suspend. **(Critical)**

**Where:** `dialer.go:245-247` (30 s default) + `dialer.go:248-250` (keepalive commented out) + `dialer.go:149-156` (`dial()` returns `nil` without dialing while `status() == StatusActive`) + `dialer.go:129-139` (`status()` is nothing but `select { case <-c.conn.Context().Done(): }`).

**Evidence — the timing is exact.** From `xray (3).log`, the complete dial timeline across the Doze episode:

```
20:35:55.878  dialing to udp:78.17.84.51:1935   ← last fresh dial before Doze
20:36:00.942  last real traffic
   [screen off, deep sleep]
20:39:32.751  SCREEN ON  (app: 01:39:32.751)
20:39:34.338  Logger started / read Android Tun Fd 102   ← in-process xray restart #1  — NO dial
20:39:39.439  Logger started / read Android Tun Fd 102   ← restart #2                 — NO dial
20:39:58.567  Logger started / read Android Tun Fd 102   ← restart #3                 — NO dial
20:40:02.834  dialing to udp:78.17.84.51:1935            ← FIRST fresh dial
20:40:03.106  congestion bbr                             ← up in 272 ms
```

`20:40:02.834 − 20:39:32.751 = **30.08 s**`, measured from screen-on — not from the last received packet (which was ~3.5 min earlier in wall-clock). Linux `CLOCK_MONOTONIC`, which Go's runtime uses for timers, **does not advance across suspend** (that is `CLOCK_BOOTTIME`'s job). So quic-go's idle deadline was effectively frozen for the whole sleep and the 30 s countdown restarted at resume. Three in-process xray restarts inside that window were all futile — `stopXray` closes the `core.Instance` but not the package-global `manger`, so `dial()` saw `StatusActive` and returned without dialing (`dialer.go:151`), and the probes were tunnelled onto a corpse (`20:39:34.376 proxy/hysteria: tunneling request to udp:1.1.1.1:53 via UDP:78.17.84.51:1935` with no preceding `dialing to`).

**This refines Report B's P1, and I disagree with one of its conclusions.** B is right that the pool is not permanently stale and that the 30 s is the idle timer. But B's proposed `keepAlivePeriod: 2` will **not** keep the NAT mapping alive through deep Doze — the ping timer is frozen along with everything else. And `maxIdleTimeout: 5` will not fire 5 s after the handover if the device is asleep; it will fire 5 s after wake. The config change is still worth making (it turns a 30 s post-wake stall into a 5 s one, and it genuinely helps the screen-on handover case where the CPU is running), but it is a mitigation, not a cure, and it must not be sold as one.

### P3 — The tun is destroyed and rebuilt on every recovery. That is unnecessary, and it costs a traffic leak, a probe false-positive window, and an fd-number use-after-free. **(High)**

**Where:** `:188` (`stopTunnel()` inside `runConnect`), `:340-346`, `:299-304`.

A `VpnService` tun is a layer-3 interface with a fixed address (`10.77.0.2/32`) and a default route. It has **no relationship whatsoever** to which physical network carries the encapsulated traffic. Nothing about a Wi-Fi↔cell handover requires rebuilding it. Rebuilding it buys nothing and costs three things:

1. **A leak window.** Between `tunInterface.close()` (`:343`) and the new `establish()` (`:200`) the VPN network is torn down and the per-UID routing rules are removed; every app's traffic on the device egresses in the clear. In-process this is ~19 ms of fd churn (`01:39:34.285` → `.304`) but the framework teardown/rebuild is asynchronous and longer — P1's evidence shows our own traffic still escaping ~33 ms *after* establish returned. Across the process-kill design the window is far worse: kill at `02:16:09.861`, new tun at `02:16:32.109` = **22.2 seconds of completely unprotected traffic**, on a recovery the user never asked for.
2. **P1's false-positive window** exists only because the tun was rebuilt.
3. **An fd-number use-after-free.** The numbers recycle deterministically, visible in the log:
   ```
   01:39:34.285 xray stop: (closing dupFd=101)   01:39:34.285 stopTunnel: tun interface closed  [master 102]
   01:39:34.304 tun established: fd=103          01:39:34.305 setTunFd(102)
   01:39:39.395 xray stop: (closing dupFd=102)   01:39:39.395 tun interface closed  [master 103]
   01:39:39.411 tun established: fd=103          01:39:39.421 setTunFd(102)   ← same numbers again
   ```
   `AndroidTun.Close()` is a no-op (`tun_android.go:47-49`) and `core.Instance.Close()` does not join the gVisor `fdbased` reader goroutines. The previous instance's reader is still blocked in `read(102)` when we close 102 and immediately `dup()` a new tun back into 102. Two live readers on the same file description; the dead instance's reader silently drops whatever it wins. I **confirm Report B's P6** and add that the fd recycling is observed, not hypothetical.

   Note also `unix.SetNonblock(fd, true)` (`tun_android.go:33`): `O_NONBLOCK` is a property of the open file description, shared through `dup()`, so this flips the master `tunInterface` non-blocking too. Harmless today; worth knowing.

### P4 — `setUnderlyingNetworks()` does not route anything, and the code comment claiming it does is the reason the handover design is mis-aimed. **(High — a correctness-of-model problem)**

**Where:** the doc comment at `:573-577`: *"Tell the system which physical network the VPN currently runs over, **so protected sockets follow it** across a Wi-Fi↔cell handover."* That is false.

`VpnService.setUnderlyingNetworks(Network[])` → `Vpn.setUnderlyingNetworks()` updates the VPN's `NetworkAgent` metadata only:
- the VPN network's **transports** become the union of the underlying networks' (hence `transport=wifi|vpn` in the old logs);
- `NOT_METERED` / `NOT_ROAMING` / `NOT_CONGESTED` / `NOT_SUSPENDED` are recomputed as the AND over them;
- link/tx bandwidth and signal strength are derived from them;
- it drives **`NetworkStats` accounting** — which physical iface the VPN's bytes are billed to;
- it feeds what apps behind the VPN see from `getActiveNetworkInfo()`.

It has **zero** effect on the fwmark or the routing table of any socket. A `protect()`ed socket follows the **system default network**, full stop.

Consequences of the wrong model: passing a stale `Network` makes the VPN advertise the wrong transports and can make it advertise `NOT_SUSPENDED=false`, which propagates to every app's `NetworkCallback`. And it created the belief that keeping `lastUnderlying` fresh is a *routing* fix, so when `refreshUnderlyingFromSystem()` was deleted from the tree the loss looked cosmetic. It is not cosmetic — but for the accounting/capabilities reason, plus the fact that it is the app's only way to *notice* a handover it slept through (`01:39:34.280 underlying refresh: STALE 111 -> 101`, on a transition for which `onAvailable` never fired). **I disagree with Report A's F4 framing** on this point: restore the re-query, yes, but not because it makes sockets follow the network.

### P5 — `protect()` alone genuinely cannot carry a live QUIC session through a handover, and no per-socket binding can either. The `bindSocket()` removal was correct, for a reason not yet stated. **(High — this closes the design question in the brief)**

The upstream socket is `lc.ListenPacket(ctx, "udp", "0.0.0.0:0")` — **unconnected**, wildcard local address, fixed local port (`system_dialer.go:80-88`). `protect()` sets the "protected from VPN" bit in the socket's fwmark via netd; it does **not** select a network. So after Wi-Fi dies:

- The kernel does a fresh route lookup **per `sendmsg`** on an unconnected wildcard socket. Packets therefore *do* keep flowing, out over `rmnet1`, with a **new source IP** and the same local port.
- The QUIC connection's identity is the 4-tuple. The server sees an unvalidated address change. The client has `DisablePathManager: true` (`dialer.go:231`, unconditional, not exposed in JSON) so it never initiates migration or path validation. Empirically the server does not rescue it either — the logs show the connection simply dead until the idle timer expires.

**So the failure is not "the socket can't send". It is "QUIC will not re-home".** That is why `Network.bindSocket()` cannot help even if it worked perfectly: pinning the socket to the new netId still changes the source IP, and migration is still required. **A fresh dial is the only possible remedy**, and I agree with Report B's P7 conclusion while strengthening its reasoning.

On the historical EPERM (marked as inference — I have no `errno` in any log): netd's `FwmarkServer` rejects `SELECT_NETWORK` with `-EPERM`/`-ENONET` when the netId does not exist or the UID may not select it. The most likely cause here is a **stale `Network` handle** — a netId captured before the handover and torn down by the time the dial ran — which is exactly the `STALE 111 -> 101` condition the log proves was routine. That would have been fixable by re-querying at dial time. It doesn't matter: **do not reintroduce it.** It cannot fix P5, and it adds a failure mode.

### P6 — IPv6 leaks around the tunnel entirely. **(High for a VPN; neither report mentions it)**

`:190-201` adds `10.77.0.2/32` and a `0.0.0.0/0` route and nothing else. Android does **not** blackhole address families a VPN omits — traffic in an unclaimed family egresses on the physical network in the clear. On cellular (`rmnet1`, per `01:39:34` / `02:16:09.810 net linkProps: 101 ifc=rmnet1`) IPv6 is nearly always present and Happy Eyeballs prefers it. Two consequences, one of them a reliability trap: apps that get an IPv6 path keep working while the tunnel is dead, masking the failure from the user, while the IPv4-literal probe reports the truth.

### P7 — Doze kills the connection through NAT expiry, not socket teardown, and nothing in the design addresses that layer. **(Medium-High)**

What actually happens at the socket/route level entering Doze: sockets are **not** closed. Two things happen — the CPU suspends between maintenance windows (so no userspace code and no Go timer runs, cf. P2), and netd's `fw_dozable` chain drops traffic for non-allowlisted UIDs (the battery-optimisation exemption at `MainActivity.kt:60-66` exempts us from that part). What kills the tunnel is the **carrier/router NAT mapping for the UDP 4-tuple expiring** — typically 30–120 s on cellular, much longer on residential Wi-Fi. With `KeepAlivePeriod == 0` nothing refreshes it. This asymmetry matters: the client can still *send*, the server's replies to the dead mapping are dropped — so from the client's point of view the socket looks fine and only the idle timer eventually notices. Corroborated by `xray (3).log:204-206, 218-219`: `proxy/hysteria: connection ends > timeout: no recent network activity`.

Does the radio re-attach as a new `Network`? Frequently, yes, and **we do not see it while asleep**: `01:39:34.280 underlying refresh: STALE 111 -> 101` — the device moved Wi-Fi→cellular during Doze and `onAvailable(101)` never reached the service. Only the explicit re-query caught it, and that re-query is deleted from the current tree.

### P8 — `onLost` leaves the VPN pinned to a dead netId. **(Medium)**

`:621-626` nulls `lastUnderlying` but never calls `applyUnderlyingNetworks(null)`. The VPN keeps advertising a torn-down network's transports and capabilities until some `onAvailable` arrives. Also, because `lastUnderlying` is nulled, the next `onAvailable` always reads as a change (`02:16:09.796 underlying changed: null -> 101`), so a Wi-Fi flap where the same network returns triggers a full recovery.

### P9 — The 22 s leak in the kill/restart path is real and unbounded on failure. **(Medium — severity depends on always-on)**

Measured: `02:16:09.861` kill → `02:16:32.109` new tun = 22.2 s with no VPN at all. In the older build the gap ran until the user manually reconnected. And `runDisconnect` calls `paramsStore.clear()` (`:311`) *before* the reconnect is known to succeed, so a failed restart leaves nothing to restore. Recovery must not share a path with deliberate teardown — I agree with Report A's P4/F5 without reservation.

### P10 — MTU 1500 on the tun. **(Low; inference)**

Proxied **TCP** is fine — gVisor advertises MSS from the tun MTU and hysteria carries it as a QUIC stream, so path MTU is irrelevant. Proxied **UDP** is not: an inner datagram near 1500 bytes cannot fit in a QUIC DATAGRAM frame (quic-go clamps to ~1200–1350). QUIC-over-QUIC (browser HTTP/3, seen throughout the log as `udp:…:443`), large DNS responses and WebRTC are the exposed cases. 1400 would be the conventional value. Low priority, and I have no packet capture to confirm drops.

**Ranking against the ~100 % goal:** P1 ≈ P2 ≫ P3 ≈ P5 > P4 ≈ P6 > P7 ≈ P9 > P8 > P10.

**Which problem explains which failure:**
- **Doze exit:** P2 (the 30 s idle timer restarting at resume, so every restart inside that window is a no-op) + P7 (dead NAT mapping) + P1 (the false-positive probe then declares victory mid-recovery). The `01:39:32 → 01:40:03` sequence is all three, in order, on one screen unlock.
- **Wi-Fi↔cell handover:** P5 (QUIC cannot re-home; migration disabled) + P2 (the pooled client stays `StatusActive` for up to 30 s of awake time and `dial()` refuses to redial) + P1 (recovery is then falsely told it succeeded).
- P4's wrong mental model is why the fix effort was aimed at `setUnderlyingNetworks` and socket binding, which are not where the failure lives.

---

## 3. How to fix it

Ordered by value. The first two are the actual fix; the rest remove the remaining ways it can fail.

### F1 — Make the probe honest. Assert the egress path, don't infer it from a reply.

`OnthecrowVpnService.kt:462-474`. Three changes, all cheap:

```kotlin
private fun probeTunnel(timeoutMs: Int): Boolean = runCatching {
    DatagramSocket().use { s ->
        s.connect(InetSocketAddress(InetAddress.getByName("1.1.1.1"), 53))
        // The decisive check: if this socket is not on the tun, the probe is meaningless.
        if ((s.localAddress as? Inet4Address)?.hostAddress != "10.77.0.2") return@use false
        s.soTimeout = timeoutMs
        val q = buildDnsQuery("cloudflare.com", PROBE_DNS_TXID)
        s.send(DatagramPacket(q, q.size))
        val r = DatagramPacket(ByteArray(512), 512)
        s.receive(r)
        r.length >= 12 &&
            ((r.data[0].toInt() and 0xFF) shl 8 or (r.data[1].toInt() and 0xFF)) == PROBE_DNS_TXID &&
            (r.data[2].toInt() and 0x80) != 0            // QR = response
    }
}.getOrElse { false }
```

**Mechanism.** `connect()` on a UDP socket forces the kernel to bind a source address *now*, from the route it will actually use, and to filter incoming datagrams by the 5-tuple. Reading `localAddress` afterwards therefore tells you, definitively, which interface the packet will leave on. `10.77.0.2` is the tun; anything else means the datagram escaped. This single line would have caught both false positives in `vpn-debug (13).log`. The txid/QR check closes the "any stray datagram counts" hole.

Additionally: **never probe within ~500 ms of an `establish()`** — but with F2 you stop rebuilding the tun, so that window largely stops existing.

*Trade-off:* on a device where the tun address is ever changed, the literal must move to a constant shared with the `Builder`. *Failure mode:* if `connect()` itself throws because no route exists at all, the probe correctly reports dead.

### F2 — Stop rebuilding the tun. Keep one tun for the session and re-dial only xray.

Split `runConnect` so recovery takes a path that does **not** call `stopTunnel()`. Concretely: add `runRedial()` that does `stopXray()` → `setTunFd(freshDup)` → `xrayEngine.start(...)`, leaving `tunInterface` untouched; `runConnect`/`stopTunnel` stay as they are for user connect/disconnect and fatal failure only.

**Why this is right at the network level:** the tun is address- and route-stable and independent of the physical link. Not rebuilding it eliminates, in one change: the per-recovery leak window (P3.1), the probe false-positive window (P1's mechanism), the destruction of every app's sockets on every recovery, and the fd-number recycling race (P3.3). It also removes the framework round-trip (`Vpn.establish()` → NetworkAgent re-registration → netd rule reinstall), which is the slowest part of the current recovery.

**Do not close the dup'd fd** (`:302`). `AndroidTun.Close()` is a no-op and `Instance.Close()` does not join the gVisor readers, so the fd number can be recycled under a live reader. Leak it — one fd per xray restart, reclaimed at process death, bounded by the recovery count. I agree with Report B's §3.5 option (a) and reject (b): a 200 ms delay is a race you cannot prove you've won.

Also make `registerProtectControllers` idempotent (`PlatformXrayEngine.android.kt:103`): `internet.RegisterDialerController` **appends** to a process-global slice (`system_dialer.go:202-207`) iterated per socket. Under in-process restarts every socket would get `protect()`ed N times. Functionally harmless, but it is N JNI round-trips on every dial and it must be fixed before F2 ships. An `AtomicBoolean` guard is enough.

### F3 — Force the pooled QUIC client to be discarded instead of waiting out a timer that doesn't run in Doze.

F2 alone is not sufficient, because of P2: an in-process restart still hits `dial()` returning `nil` on a `StatusActive` corpse. Three options, in order:

**(a) Patch libXray — the only complete fix.** `manger` is unexported and the package exports only `Dial`/`Listen`, so there is no runtime trick. The patch is small:
```go
func CloseAll() {
    manger.mutex.Lock(); defer manger.mutex.Unlock()
    for k, c := range manger.m { c.close(); delete(manger.m, k) }
}
```
exported through a libXray wrapper and called from `PlatformXrayEngine.stop()` right after `stopXray`. The repo already builds xray from source (`scripts/build-libxray-android.sh`) and already carries a custom Go package, so the machinery exists; `.libxray-build/libXray/go.mod` needs a `replace` to a fork. Recovery then becomes `stopXray → CloseAll → start` in ~250 ms, in-process, with no FGS restart, no process kill, no cross-process broadcast, no leak window. Measured cost of a genuinely fresh dial: **272 ms** (`20:40:02.834 dialing` → `20:40:03.106 congestion bbr`); `21:16:32.575 → .764` gives 190 ms. This is the single change that makes the reliability target reachable.

**(b) Rotate the pool key — works today, no patch.** The key is exactly `dest.NetAddr()` (`dialer.go:446, 450, 462`). Alternating the outbound `address` between the hostname and its resolved literal, or between two ports the server listens on, mints a **new** pool entry with freshly-captured config on every reconnect, bypassing both the `StatusActive` staleness and the config-capture bug in P-B5. Cost: the old entry lingers holding a socket until the 30 s cleaner, and entries are never deleted (`dialer.go:428-435`), so the map grows by at most a couple of entries. *Fails when:* the config carries a bare IP and the server listens on one port. Treat as a stopgap, not the design.

**(c) `finalmask.quicParams: { maxIdleTimeout: 5, keepAlivePeriod: 2 }`** as Report B proposes, injected in `XrayConfigSanitizer.withTunInbound`. Worth doing regardless — it makes the *screen-on* handover self-heal in ~5 s with no coordination. But per P2, be clear about what it does not do: the timers are frozen during suspend, so post-Doze it yields "5 s after wake", not "5 s after the network died", and the 2 s keepalive will not hold the NAT mapping open through deep sleep. Merge rather than overwrite — `share/hysteria_mask.go:12` only allocates `quicParams` when the link carries bandwidth/ports, and clobbering it would silently downgrade brutal congestion control to BBR.

**Recommendation:** (a) as the fix, (c) alongside it as defence in depth, (b) only if (a) is blocked.

### F4 — Add IPv6 to the tun, or explicitly blackhole it.

`OnthecrowVpnService.kt:190-201`:
```kotlin
.addAddress("fd00:1:2:3::1", 128)   // ULA, as sing-box/Outline do
.addRoute("::", 0)
.addDnsServer("2606:4700:4700::1111")
```
Without this, IPv6 traffic bypasses the tunnel in the clear on any IPv6-capable link (which cellular essentially always is). *Trade-off:* if the upstream server has no IPv6 egress, claiming `::/0` blackholes IPv6 inside the tunnel — which is the correct, fail-closed behaviour, and apps fall back to IPv4 via Happy Eyeballs. *Verify* that the xray tun inbound and the outbound handle v6 destinations before shipping; if it cannot, still add the route so the traffic fails closed rather than leaking.

### F5 — Correct the `setUnderlyingNetworks` usage and restore the re-query, for the right reasons.

- Fix the comment at `:573-577`. It is metadata (transports, metered/roaming/suspended, bandwidth, `NetworkStats` attribution), not routing.
- Register the network callback **before** `establish()`/`startXrayOnTun` (`:203-207`), not after, so the VPN never advertises default-derived capabilities for the first ~400 ms of a session.
- Restore `refreshUnderlyingFromSystem()` (deleted in the working tree) and call it at the top of every recovery. Scan `cm.allNetworks` for `NOT_VPN + INTERNET`, preferring `VALIDATED`; `activeNetwork` is useless because we *are* the VPN. Justification is P7: `01:39:34.280 STALE 111 -> 101` proves callbacks are missed across Doze.
- On `onLost(lastUnderlying)`, call `applyUnderlyingNetworks(null)` (`:621-626`) so we stop advertising a dead network.
- Trigger on `onCapabilitiesChanged` with `NET_CAPABILITY_VALIDATED` on a network `!= lastUnderlying`, not on bare `onAvailable`. Cellular typically appears unvalidated and validates 1–3 s later; redialling at `onAvailable` burns an attempt on a link that cannot carry the QUIC handshake yet. (Report A's F4 is right about the trigger; only its stated reason is wrong.)

### F6 — Recovery must not share a path with teardown, and must not leak while it runs.

- Split `runDisconnect` so only the user/revoke path calls `paramsStore.clear()` (`:311`). Recovery keeps the persisted config. (Report A F5 — I concur.)
- Once F2 + F3 land, recovery never kills the process and never closes the tun, so the 22 s leak (`02:16:09.861` → `02:16:32.109`) disappears entirely. Keep the process-kill path as a last-resort tier only, for user-initiated config switches — which is also the correct place for it given the pool's config-capture bug (`dialer.go:449-465` reuses `c.config`, i.e. the old auth string, on a key hit).
- **Recommend always-on VPN + lockdown in-app**, deep-linking to `Settings.ACTION_VPN_SETTINGS`. At the network layer, lockdown installs system-owned rules that blackhole the UID ranges whenever the VPN is down, so any residual gap — process death, OEM kill, a crash — **fails closed instead of leaking**. It also makes the system responsible for (re)starting the `VpnService`, which is a far stronger restart engine than `START_STICKY` or an alarm. It cannot be granted programmatically; it is a user setting, so it is a recommendation, not a mechanism you can rely on.

### F7 — Stop deferring on screen-off for network changes.

`:609-613`. A handover with the screen off is precisely the case that must work, and post-F2/F3 the cost is one re-dial (~250 ms of CPU) rather than a process restart. Keep cancelling the 8 s polling loop on `SCREEN_OFF` — that is the actual battery cost — and replace the screen-off silence with a much slower probe (~60 s) or an idle-mode-exit trigger. Note that `delay()` will not fire during suspend either, so a screen-off cadence is best-effort by construction; that is fine, because the goal is bounding the dead window, not eliminating it.

---

## 4. Open questions / things I could not verify

1. **The exact mechanism of the probe escaping the tun (P1).** The correlation is airtight and the consequence is proven, but the causal chain — "establish() returns before netd has installed the UID routing rules" — is inference. **Experiment:** in `probeTunnel`, log `socket.localAddress` on every probe. If the two false-positive probes show a physical address (`192.168.x.x` / the rmnet address) rather than `10.77.0.2`, it is settled, and F1 fixes it by construction. This is a two-line change and should be the first thing done.
2. **Whether the same leak affects app traffic, not just our probe.** If the framework really has an unrouted window after `establish()`, every app leaks for those tens of milliseconds on every recovery. **Experiment:** `adb shell ip rule` / `ip route show table all` in a tight loop around a recovery, or a `tcpdump` on `rmnet1` filtered to non-tunnel destinations.
3. **The historical `bindSocket()` EPERM.** No `errno` and no netId are in any log, so my stale-netId explanation is inference. It does not affect the recommendation (do not reintroduce it — P5 shows it cannot help), but if you ever want certainty, log `errno` and the netId at the failure site.
4. **Whether `CLOCK_MONOTONIC` freezing across suspend fully explains the 30.08 s (P2).** The number is a striking match but it is one observation. **Experiment:** ship `maxIdleTimeout: 5`, sleep the device 10 minutes, unlock, and measure the delay to the next `dialing to udp:` in `xray.log`. If it lands ~5 s after screen-on (and *not* ~5 s after the network changed while asleep), the model is confirmed and F3(c)'s limits are established.
5. **IPv6 through the xray tun inbound and the hysteria outbound.** I did not verify that `proxy/tun` + the hysteria outbound handle IPv6 destinations in this build, so F4's "add the route" is safe but "add the address and expect it to work" needs a test with a v6-only destination.
6. **MTU (P10).** No packet capture, so the 1500-byte proxied-UDP concern is theoretical. **Experiment:** `ping -M do -s 1400` to a v4 host through the tunnel, and check for `MaxDatagramFrameSize` drops in xray at `debug`.
7. **The server's advertised `max_idle_timeout`.** If the hysteria2 server advertises below 5 s, F3(c) is a no-op (`min()` of the two wins). Unlikely — hysteria2 defaults to 30 s, and the observed 30 s stall is consistent with the client's own default winning — but I have no server visibility.

---

## Grooming notes

### Android / Doze

# Grooming note — Android / Doze lens (Report A author)

I re-verified everything below in the working tree and in `vpn-debug (13).log` myself. Where I changed my own position from Report A, I say so.

---

## 0. Three facts I established during grooming that change the shape of the debate

**Fact G1 — the main process is provably killed while the tunnel runs, and broadcasts to it are provably deferred 39–68 s.** In `vpn-debug (13).log`: main pid **26986** logs last at `01:40:42.462`; the next main-process line is pid **5982** at `02:16:00`. Different pid = the process was killed. And the four `Connected` broadcasts sent by `:vpn` at `01:39:34.363 / 01:39:39.444 / 01:39:58.579 / 01:40:03.648` are all received in one batch at `01:40:42.460–.462` — **68 s / 63 s / 44 s / 39 s** of cached-process broadcast deferral. This is not inference any more. **Any design that routes recovery through `sendBroadcast` to a runtime-registered receiver in the main process is dead on arrival.** That kills the current `recover()` (`OnthecrowVpnService.kt:405-413` → `VpnStatusBroadcast.kt:45-48` → `PlatformVpnController.android.kt:42-44`) outright, and it also kills Report B's §3.2 "Tier 3" and Report C's F6 fallback as written.

**Fact G2 — Report B's proposed in-process tier ladder has already been shipped and has already failed in the field.** The `01:39:32 → 01:40:03` sequence in log 13 is *not* the current build. It is an earlier build that had exactly what B §3.2 proposes: `recover (screen on) attempt 1/4 … attempt 2/4`, `runConnect: restart=true forceFull=true tunUp=true`, `underlying refresh: STALE 111 -> 101`, in-process xray restart, retry ladder. It produced: attempt 1 "not healthy", attempt 2 → **`tunnel probe OK after 39ms` → `tunnel healthy — done`** → keepalive failed 9.5 s later → identical cycle → false "healthy" again at `01:40:03.687`. So the in-process ladder is not a new idea to be evaluated on paper; it was tried, and it lost to (a) the stale QUIC client and (b) a lying probe. **B §3.2 must not be re-proposed without F3(a)+F1 landing first.** Report B did not know this log was a different build; that is the single biggest error across the three reports.

**Fact G3 — I am softening my own P6.** The log proves `02:16:09.861 killing :vpn process` → **20.2 s** of nothing → `02:16:30.071` the *user* pressed disconnect. That proves "no restart within 20 s", **not** "never restarts". My Report A P6 overclaimed. The likely mechanism is not FGS-start illegality but AMS service-restart backoff: `ActiveServices` uses `SERVICE_RESTART_DURATION` (1 s) with a ×4 escalation per restart inside `SERVICE_RESET_RUN_DURATION`, so after the repeated self-kills this session the pending delay was plausibly 16 s / 64 s, and the user beat it. Practical conclusion is unchanged — **START_STICKY is unusable as a recovery engine because its latency is unbounded and grows with every use** — but the reason matters, because it also means *any* repeat-kill design (including my own F2) degrades the more it fires.

---

## 1. Contradictions between the three reports

| # | Disagreement | Adjudication |
|---|---|---|
| C1 | **B P1**: the 30 s stall is the QUIC idle timer and `maxIdleTimeout:5 + keepAlivePeriod:2` largely cures it. **C P2**: the timer runs on `CLOCK_MONOTONIC`, which is frozen across suspend, so post-Doze it yields "5 s after wake", not "5 s after the link died"; the 2 s keepalive cannot hold a NAT mapping through deep sleep. | **C is right, decisively.** Linux `CLOCK_MONOTONIC` does not advance across suspend (that is `CLOCK_BOOTTIME`); Go's runtime timers use `CLOCK_MONOTONIC`. C's arithmetic (`20:40:02.834 − 20:39:32.751 = 30.08 s` measured **from screen-on**, not from the last packet) is the decisive evidence and B has no counter-measurement. The config change is still worth shipping — it fixes the *screen-on* handover — but it is a latency mitigation, not a reliability fix. B's framing ("makes P1/P2/P4/P5/P6/P11 moot") is wrong. |
| C2 | **A P1/P2** and **B P2**: recovery-by-broadcast is broken because the main process is dead. **B** marks it "inference — I cannot prove main-process death from log 13". | **Confirmed as fact, not inference** — see G1 (pid 26986 → 5982, plus the 39–68 s deferral batch). B's caveat is now obsolete. |
| C3 | **A F4** says restore `refreshUnderlyingFromSystem()` because `setUnderlyingNetworks` makes protected sockets follow the network. **C P4** says that is false — it is metadata/accounting only. | **C is right and I concede my own framing was wrong.** `setUnderlyingNetworks` feeds the VPN `NetworkAgent`'s transports/capabilities/`NetworkStats` attribution; it does not touch any socket's fwmark or routing table. A `protect()`ed socket follows the **system default network**. The re-query must be restored anyway — but as a *detector* of handovers we slept through (`01:39:34.280 underlying refresh: STALE 111 -> 101`), not as a routing fix. |
| C4 | **C P3** says "the tun is destroyed and rebuilt on every recovery". **A/B** describe the tun as held open across re-dials. | **C is right about the code, both others are reading a stale comment.** `runConnect` calls `stopTunnel()` at `OnthecrowVpnService.kt:188`, and `stopTunnel()` closes the master `tunInterface`. The doc comment on `startXrayOnTun` ("we dup so xray can be stopped/restarted on every re-dial without ever closing the master") describes a `keepTun` path that **no longer exists in the tree** — there is no in-process re-dial caller at all in the current build. That comment is now actively misleading and should be deleted or the path restored. |
| C5 | **C P1** (probe escapes the tun and returns a false "healthy"). A and B did not find it. | **C is right that the sensor lies; I cannot fully adjudicate the mechanism.** The correlation in log 13 is airtight and I re-read `probeTunnel` (`:462-474`) to confirm the two enabling defects: the `DatagramSocket` is never `connect()`ed (any stray datagram from any source counts) and `PROBE_DNS_TXID` is built into the query and **never checked in the response**. The causal story ("`establish()` returns before netd installs the per-UID rules") is plausible and matches the ≤ 40 ms-after-establish signature, but I can offer a second candidate in the same family (ConnectivityService tearing down the old VPN `NetworkAgent` and re-registering, briefly leaving the UID on the physical default). **It does not matter which**: C's F1 fix — assert `socket.localAddress == 10.77.0.2` after `connect()` — is correct under *both* mechanisms. Ship it. |
| C6 | **A P6 / established fact #4**: START_STICKY never restarts the service. **C/B** accept it. | **All three overclaim.** See G3. Correct statement: unbounded and escalating restart latency; unusable as a recovery engine; not proven to be a hard failure. |
| C7 | **B §3.5(b)** (delay the dup'd-fd close by ~200 ms) vs **C F2** (never close it, leak one fd per restart). | **C is right.** A timed delay against un-joined gVisor reader goroutines is a race you cannot prove you won, and C observed the fd numbers actually recycling (`101/102/103` reused across cycles). Leak it; process death reclaims. |
| C8 | **Cannot adjudicate (out of my domain):** whether `manger` is truly unreachable without a fork; whether `finalmask.quicParams` is genuinely absent from the emitted 1101-byte config; the server's advertised `max_idle_timeout`; the MTU-1500 proxied-UDP concern. B and C agree on the first two, which is the strongest signal available, but the config field is still **unverified against a real emitted JSON** — see §2. |

---

## 2. Adversarial check — where each proposed fix breaks

**A-F1/F2 (my own: exact alarm + manifest receiver as the restart engine). Partially refuted — I am withdrawing it as the primary.**
- Deep Doze rate-limits `setExactAndAllowWhileIdle` to roughly one firing per ~9–10 min per app. A handover during a 3-hour screen-off period gets *one* shot; if it lands on an unvalidated cellular link (see below), the tunnel stays dead until morning.
- The temporary power allowlist granted by an exact alarm is real, but the FGS-start it enables still races AMS's service-restart backoff for the *same* component (G3).
- Xiaomi/HyperOS "Autostart" disabled blocks manifest-receiver process starts entirely. On the actual device under test this is a coin flip.
- **Keep it, demoted to a last-resort tier only.** It should never be the mechanism the common case depends on.

**A-F7 / C-F6 (always-on VPN + lockdown).** Not refutable as a *mechanism* — it genuinely makes the system the restart engine and makes gaps fail closed instead of leaking. But it is a user setting with **no programmatic grant and no public read API**, so you cannot detect whether it is on, cannot require it, and cannot ship a design that assumes it. Recommendation-only. It does not count toward the 100 % target.

**B-§3.1 / C-F3(c) (`finalmask.quicParams: {maxIdleTimeout:5, keepAlivePeriod:2}`).** *Flagging explicitly per the brief: this is an **unverified libXray/xray config field** as far as anything observed at runtime.* The field names were read from source, which is good, but nobody has seen the emitted 1101-byte JSON, and `XrayConfigSanitizer` silently passing an unknown key through `finalmask` would look identical to success. **Gate it behind a one-line runtime assertion** (log the emitted `runtimeJson` once at `OnthecrowVpnService.kt:245`, and check `xray.log` for a changed idle-timeout behaviour) before any other change depends on it. Additional refutations: it is a no-op if the server advertises lower; it must **merge**, not overwrite, or it silently downgrades brutal → BBR; and per C1 it does nothing for the Doze case.

**B-§3.2 (in-process tier ladder as the primary recovery).** **Refuted by G2** — this exact design shipped and failed in the field on `06-17 01:39`. It becomes viable only after C-F1 (honest probe) and C-F3(a) (real pool flush) land. Its "Tier 3" fallback (manifest-register the recover receiver in the main process) is separately refuted by G1: even a manifest receiver that cold-starts the main process must then issue a background `startForegroundService` from a UID that just lost its only FGS, and it eats the broadcast-dispatch latency.

**C-F3(a) (patch libXray with `CloseAll()`).** The strongest proposal in all three reports, and I cannot refute the mechanism. Its risks are operational, not technical: a `replace` to a fork in `.libxray-build/libXray/go.mod` is now a permanent maintenance obligation, and C's own P5 (the pool caches `c.config`, i.e. the **auth string**, keyed only on `host:port`) means a flush must also be invoked on user config switches or you get silent wrong-credential reuse. Accept both costs.

**C-F3(b) (rotate the pool key by alternating hostname/IP or port).** Refuted for this deployment: the observed config is a bare `hysteria2://` link and the dial target in `xray (4).log` is a **literal IP on a single port** (`udp:78.17.84.51:1935`). There is nothing to alternate. Stopgap only, and not one available here.

**C-F4 (add IPv6 to the tun).** Correct as a leak fix, but it is a *reliability regression risk*: if the hysteria outbound has no v6 egress, claiming `::/0` blackholes IPv6 inside the tunnel and every Happy-Eyeballs app now takes a 250 ms fallback penalty on every connection. Ship it **after** the reconnect work, behind a verified v6 egress test. Do not bundle it into the recovery change — it will confound the field measurement.

**Anything relying on `START_STICKY`:** the `onStartCommand(null)` self-heal branch (`:136-149`) is fine to *keep* as a free bonus, but nothing may be sequenced on it.
**Anything relying on the main process being alive:** `recover()`, `onRecoverRequested`, `reconnect()`, and `lastXrayJson` must all leave the critical path.

---

## 3. What all three of us missed

1. **`VpnSyncWorker` is a fourth actor on the tunnel and nobody modelled it.** `feature/connection/logic-impl/.../VpnSyncWorker.kt:66-76` collects `vpnController.status` in the main process and sets `activeKey = null` on `Disconnected`/`Error`, and calls `vpnController.revoke()` on `revokedActive`. Recovery routed through main-process `disconnect() → connect()` therefore drives this worker through `Disconnected → Connected` on every recovery, resetting `activeKey`. Consequence: a genuine remote config change that arrives during a recovery is **swallowed** (`handleConnected` sees `previous == null` and just records). Visible in the log as the `WORKER observe: … activeKey=true → false` flip at `02:16:30–32`. Moving recovery into `:vpn` (which I recommend anyway) fixes this as a side effect — but only if recovery stops emitting `Disconnected` to the main process at all.
2. **`ConnectivityManager` callback registration is leak-prone and hard-capped.** `stopMonitoring()` (`:492-506`) unregisters inside `runCatching { }` and swallows failures; `startMonitoring()` re-registers. Every in-process recovery cycles this pair. AOSP enforces a per-UID limit of 100 concurrent `NetworkRequest`s and throws `TooManyRequestsException` past it — which, in a long-lived `:vpn` process doing in-process recovery (the design we are converging on), is a real ceiling. Register the callback **once per process** and never unregister it until `onDestroy`.
3. **The probe is the only liveness signal and it runs unconditionally.** Nobody proposed **demand-gating**: a dead tunnel only matters when something is trying to use it. Reading the tun's rx counter (or `TrafficStats` for the VPN interface) gives a free, zero-packet "is anyone actually behind this tunnel" signal, which is what makes a screen-off probe cadence affordable.
4. **Nobody stated the ordering constraint that actually broke the old build.** At `01:39:39` the sequence was `establish()` → `startXray` → `probe` within 73 ms. Whatever the exact mechanism, a probe issued inside the tun-reconfiguration window is meaningless. The fix is not only C-F1's source-address assertion but a hard rule: **never probe within 500 ms of an `establish()`, and never treat a probe issued before the first post-establish `onAvailable` as authoritative.**
5. **Nobody costed the leak.** `02:16:09.861` kill → `02:16:32.109` new tun = **22.2 s with no VPN and no lockdown** — all device traffic in the clear, on a recovery the user never asked for. For a VPN this is a correctness bug, not a latency bug, and it is on its own sufficient grounds to delete the process-kill path from the common case.

---

## 4. Converged recommendation — what I would ship

**Design principle:** recovery lives entirely inside `:vpn`, never crosses a process boundary, never closes the tun, and never kills the process. The main process is a UI mirror. The process kill survives only as tier 4.

### Phase 0 — instrumentation, ship first, one build (half a day)
1. `OnthecrowVpnService.kt:462-474` — log `socket.localAddress` on every probe. Settles C's P1 mechanism.
2. `OnthecrowVpnService.kt:245` — log the emitted `runtimeJson` once. Settles whether `finalmask.quicParams` exists (unblocks the one unverified config field).
3. Field test: handover + Doze exit, pull both logs.

### Phase 1 — the fix
4. **Honest probe.** `OnthecrowVpnService.kt:462-474`: `connect()` the `DatagramSocket`, assert `localAddress == 10.77.0.2` (hoist the literal to a constant shared with the `Builder` at `:193`), verify the DNS txid and the QR bit. Refuse to probe within 500 ms of an `establish()`. *(C-F1; adjudicated correct under either mechanism.)*
5. **Patch libXray: export `CloseAll()` for the hysteria pool**, call it from `PlatformXrayEngine.android.kt:132-145` immediately after `stopXray`. Also call it on user config switches (C-P5: the pool caches the auth string keyed on `host:port`). *(C-F3(a) — the one change that makes the target reachable.)*
6. **Stop rebuilding the tun on recovery.** Split `runConnect`: add `runRedial()` = `stopXray → CloseAll → setTunFd(freshDup) → start`, leaving `tunInterface` untouched. `stopTunnel()` (`:188`, `:340-346`) is reached only from user connect/disconnect/revoke/fatal. **Do not close the dup'd fd** (`:302`) — leak it. Delete the now-false comment at `:60-63`/`:227-231`.
7. **Make `registerProtectControllers` idempotent** (`PlatformXrayEngine.android.kt:103`) with a process-level `AtomicBoolean` — mandatory before (6) ships, since the controller slice is append-only and global.
8. **Delete the cross-process recovery path**: `sendRecoverRequest`/`registerRecoverRequest` (`VpnStatusBroadcast.kt:45-59`), `onRecoverRequested`/`reconnect` (`PlatformVpnController.android.kt:47-77`). *(Refuted by G1.)*
9. **Split teardown from recovery**: `runDisconnect(stopService)` → `runUserDisconnect()` (only this calls `paramsStore.clear()`, `:311`) and `runRecoveryRestart()` (keeps params, keeps `Connecting`). Recovery must never emit `Disconnected` — that is also what stops `VpnSyncWorker` from being dragged through a phantom cycle (gap 3.1).
10. **Wakelock.** Add `WAKE_LOCK` to `androidApp/src/main/AndroidManifest.xml` (absent today — verified). Acquire a `PARTIAL_WAKE_LOCK` with a 20 s timeout at the top of the recovery ladder, release in `finally`.
11. **Trigger set** (`OnthecrowVpnService.kt:508-533`, `:591-645`), all screen-state-independent:
    - `onCapabilitiesChanged` with `NET_CAPABILITY_VALIDATED` true on a network `!= lastUnderlying` — **not** bare `onAvailable` (cellular validates 1–3 s late; re-dialling early burns the attempt).
    - `onLinkPropertiesChanged` when addresses/routes change on the current underlying (only signal for a same-netId route change; currently a no-op at `:617-619`).
    - `onLost(lastUnderlying)` → `applyUnderlyingNetworks(null)`; track last-*available* by netId so a Wi-Fi flap (`02:16:09.625/.705`) is not read as a change.
    - **`ACTION_DEVICE_IDLE_MODE_CHANGED`** receiver, exit-from-idle → refresh + probe + recover. This, not `SCREEN_ON`, is the Doze-exit trigger.
    - `ACTION_SCREEN_ON` → refresh + probe (kept, as a fast path).
    - Restore `refreshUnderlyingFromSystem()` (deleted in the working tree) at the top of every recovery and every idle-exit — scan `cm.allNetworks` for `NOT_VPN+INTERNET`, prefer `VALIDATED`. Justified by `01:39:34.280 STALE 111 -> 101`, **as a detector, not a routing fix** (C3).
    - Register the callback **once per process**, before `establish()`, and never unregister until `onDestroy` (gap 3.2).
12. **Recovery ladder**, mutex-guarded, `NonCancellable` critical section, wakelock held, running in `:vpn`:
    - T0: `refreshUnderlying` → `applyUnderlyingNetworks` → probe. (~50 ms)
    - T1: `runRedial()` (stopXray → CloseAll → start) → wait 500 ms → probe. Retry once. (~250 ms each, measured: 190–272 ms for a genuine fresh dial.)
    - T2: full rebuild — close and re-`establish()` the tun, then T1.
    - T3: `Process.killProcess` + `setExactAndAllowWhileIdle(now+1500 ms)` → **manifest** receiver in `:vpn` → load `ConnectionParams` → `startForegroundService(ACTION_CONNECT)`. Requires new manifest receiver + `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`/`RECEIVE_BOOT_COMPLETED`. Rate-limited to once per 10 min; escalating AMS backoff means it degrades with use (G3). **Last resort only.**
13. **Cadence**: keepalive probe 8 s screen-on (unchanged), **60 s screen-off** (do not cancel `tunnelJob` on `SCREEN_OFF`, just slow it), 2 consecutive failures → ladder. Probe timeout 600 ms, not 1500 ms (`:728`). Recovery debounce 6 s → **record only after a confirmed successful recovery**, not before the attempt (`:410`).

### Phase 2 — defence in depth, after Phase 1 is measured
14. `finalmask.quicParams: {maxIdleTimeout: 5, keepAlivePeriod: 2}`, **merged not overwritten**, in `XrayConfigSanitizer.kt:51-110` — only once Phase 0 (2) confirms the field lands. Helps the screen-on handover; per C1 it does nothing in Doze.
15. `BOOT_COMPLETED` restore; in-app prompts for always-on VPN + lockdown (`Settings.ACTION_VPN_SETTINGS`) and HyperOS Autostart.
16. IPv6 on the tun — separately, behind a verified v6-egress test.

### Expected outcome
- **Screen-on handover:** ~100 %, T0/T1, **300–800 ms**.
- **Doze exit / idle-mode-exit:** ~100 % at tier T1, **~1 s from the wake event**; worst case bounded at 60 s (screen-off probe cadence) rather than "until the user unlocks".
- **Leak window per recovery:** 22.2 s → **0** (the tun is never closed).

### What could still break it
- **`CloseAll()` not landing** (fork blocked, or `manger` semantics differ from B/C's source read) — then T1 degrades to the old 30 s stall and the whole design falls back to T3, which is *not* 100 %. This is the single load-bearing dependency and it is out of my domain to verify.
- **OEM kill of `:vpn` itself.** Nothing app-side beats HyperOS if it decides to kill a foreground service; only always-on VPN, which cannot be enforced.
- **The probe still lying** for a reason other than source-address escape (e.g. the `com.google.android.gms` split-tunnel exclusion interacting with DNS — the unexplained `01:39:49`→`01:39:58` window in my Report A open question 7 is still not fully accounted for). Phase 0 instrumentation is what closes this.
- **T3's exact-alarm quota and AMS restart backoff** — accepted, because Phase 1 is designed so T3 essentially never runs.

---

### Go / libXray

# GROOMING NOTE — Go / libXray lens (Report B author)

Verified this round: `PlatformXrayEngine.android.kt:103` calls `registerProtectControllers` inside every `start()`, and it appends two fresh `Proxy` instances to xray's global controller slice — confirmed append-only, no dedup. `AndroidVpnSocketProtector` is a process-global `@Volatile` lambda holder, so the protect path itself is restart-safe (good news for in-process restarts). `dialer.go:245-249` re-confirmed: `MaxIdleTimeout=30s` default, `KeepAlivePeriod` default commented out, `manger` package-global at `:437`.

---

## 1. CONTRADICTIONS

### C1 — Why the ~30 s stall happens. **C is right; I was half-wrong; A was wrong.**
- **A** (fact #3 restated): stale pool, evicted by "xray's internal idle timeout". Vague, and wrong about eviction — `clientManager.clean()` (`dialer.go:428-435`) never deletes entries.
- **B (me)**: quic-go `MaxIdleTimeout=30s` with keepalive disabled. Right mechanism.
- **C**: same mechanism, **plus** the timer is on `CLOCK_MONOTONIC`, which does not advance across Android suspend, so the 30 s is counted **from CPU resume**.

**Adjudication: C wins, and this is the single most important correction in the grooming.** Go's runtime `nanotime` is `clock_gettime(CLOCK_MONOTONIC)`; `time.Now()`'s monotonic reading and all runtime timers ride it; `CLOCK_BOOTTIME` is the suspend-inclusive one and Go does not use it. C's measurement (`20:39:32.751` screen-on → `20:40:02.834` first fresh dial = 30.08 s) is not a coincidence — it is the idle timer restarting at resume. My §3.1 claim that `maxIdleTimeout:5` makes the pool "self-heal in ~5 s" is **wrong for the Doze case**: it yields 5 s *after wake*, not 5 s after the link died. I withdraw it as a cure and keep it only as a screen-on-handover mitigation.

**Corollary neither report drew:** the same freeze applies to the *app's* timers. `delay(8_000)` in `keepAliveLoop` (`OnthecrowVpnService.kt:434-450`) and the 1500 ms probe timeout are `CLOCK_MONOTONIC`-based too. Only the recovery debounce (`SystemClock.elapsedRealtime()`, `:405-413`) is `BOOTTIME` and therefore suspend-aware — which means **the debounce is the one timer that keeps running while everything else is frozen**, i.e. it can expire "early" relative to every other timer in the system. Minor, but it means the 6 s debounce is not comparable to the 8 s keepalive interval.

### C2 — Does `keepAlivePeriod: 2` hold the NAT mapping through Doze? **C is right. No.**
I proposed it partly for NAT retention. Frozen timer ⇒ no pings ⇒ mapping expires (30–120 s cellular). C's P7 is correct and my §3.3's framing ("that is fine and in fact desirable") was hand-waving a defect into a feature. It *is* survivable, but only because F1/F3 make the post-wake redial fast — not because the keepalive works.

### C3 — What `setUnderlyingNetworks()` does. **C is right; A is wrong.**
A's F4 restores `refreshUnderlyingFromSystem()` on the theory that a stale `Network` breaks routing. It does not: `Vpn.setUnderlyingNetworks()` mutates NetworkAgent metadata (transports, metered/roaming/suspended, bandwidth, `NetworkStats` attribution). It sets no fwmark. Outside my core domain but adjacent, and C's account matches the framework. **Restore the re-query anyway**, for C's reason: it is the app's only way to *detect* a handover it slept through (`01:39:34.280 STALE 111 -> 101`, with no `onAvailable`).

### C4 — Is the pool "permanently stale"? **B/C agree against the project premise.**
The brief's framing ("only a fresh process clears it") is false as stated. `dial()` self-heals at `dialer.go:149-156` once `c.conn.Context()` is Done. The pool is stale *for the duration of the idle timer*, which post-suspend is 30 s of awake time. The process kill works by accident — it bypasses a timer, not a permanent cache. **Cannot be fully adjudicated without the experiment in §4.3 of my report** (ship `maxIdleTimeout:5`, measure). But the code is unambiguous.

### C5 — Probe false positives. **C's P1 is new and, I believe, correct. Neither A nor I found it.**
The correlation (two "probe OK after ~38 ms" verdicts with no matching `proxy/tun: processing from udp:10.77.0.2` line in the xray log, both sent ~33 ms after `establish()`) is strong. **Cannot adjudicate the causal mechanism** (async netd rule installation) from my lens — it is a framework question. But the *consequence* is verifiable from the xray side and it is decisive: recovery declared victory on a tunnel that xray was still tunnelling onto a corpse. C's one-line `localAddress == 10.77.0.2` assertion settles it either way and costs nothing. **Ship it first.**

### C6 — Tun rebuild on every recovery. **C is right; my report and A's both missed it.**
I wrote that the code "already correctly keeps `tunInterface` open (`:222-231`, `:339-346`)". C found that `runConnect:188` calls `stopTunnel()`, which closes the master, and that the `keepTun` path was deleted from the tree. **C is correct and I was wrong** — I read the helper in isolation and not its caller. This matters: it means there is no existing in-process redial path to build on; §3.2 Tier 1 has to be written, not just re-enabled.

### C7 — fd close (`OnthecrowVpnService.kt:299-304`). **B and C agree (leak the dup); A didn't cover.** C additionally *observed* the fd numbers recycling (101/102/103 alternating across cycles), upgrading my P6 from latent to demonstrated. Take option (a): never close the dup.

### C8 — Whether the main process is alive at recovery time. **A has evidence; B and C inferred.**
A's `vpn-debug (13).log` finding (main pid 26986 last logs `01:40:42`, `:vpn` pid 27094 recovers at `02:15:52`, next main pid appears only when the user launched the app) is the strongest evidence in any of the three reports for P-A1/P-B2. **Adjudicated in A's favour.** The broadcast-to-main design is dead regardless of anything in my domain.

### C9 — Un-adjudicable from my lens
- Whether `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` actually exempts background FGS start (A's open Q2).
- Why START_STICKY never fires (A's P6).
- Android 15/16 `specialUse` FGS restrictions.
- The exact netd/establish() ordering behind C's P1.

---

## 2. ADVERSARIAL CHECK

**Refuting my own §3.1 (`finalmask.quicParams`).** Fails under Doze — C2/C1. Also: `quicParams` is an **unverified-in-practice config field for this pipeline**. I verified the JSON tags, the bounds (`[4,120]` / `[2,60]`) and the consumption path in `dialer.go:222-247`, but I never saw the generated 1101-byte config, so I have not proven `XrayConfigSanitizer` output survives `share/hysteria_mask.go`'s structure. Per the brief's rule: **flagging this as an unverified libXray config field.** It must not be load-bearing. Downgrade to defence-in-depth, and gate shipping it on logging `runtimeJson` once.

Second failure mode: `maxIdleTimeout: 5` on a lossy cell link tears down a *healthy* connection during a 5 s stall (elevator, tunnel, congested cell). Each teardown costs a full re-handshake + hysteria auth. On a bad link this can oscillate. `8` / `3` is the safer setting; I'd ship 8/3, not 5/2.

**Refuting `CloseAll()` patch (my §3.4 note / C's F3a).** The strongest proposal on the table. Attack surface:
- `c.close()` (`dialer.go:141-147`) nils `conn`/`pktConn`/`udpSM`. If another goroutine is mid-`c.tcp()`/`c.udp()` on that client, we have a **nil-deref race in the :vpn process** — a Go panic is an uncatchable process crash. `client` has a mutex; `CloseAll` must take the *client* lock, not just `manger.mutex`, and `close()` must be idempotent. This is the real risk of the patch and neither C nor I stated it.
- Ordering: must be called **after** `StopXray` returns, or in-flight outbound handlers will immediately re-populate the map.
- It does **not** cover non-hysteria transports. If the user's config ever uses plain QUIC/vless-over-QUIC (the brief mentions vless over QUIC), `transport/internet/quic` has its **own** `clientConnections` global with the same shape. The patch must cover both or the fix is config-dependent.
- Build cost: a `replace` to a xray-core fork in `.libxray-build/libXray/go.mod`, maintained across upstream bumps. Real but small; the repo already carries `onthecrow_convert/`.

**Refuting pool-key rotation (my §3.4 / C's F3b).** Probably **not available to this user**. `xray (4).log:12` dials `udp:78.17.84.51:1935` — an IP literal, and `dest.NetAddr()` would print a domain if the config carried one. Single IP, single port ⇒ no alternate key. *(Inference: I did not read the config JSON.)* Also, rotating the key leaves the old entry holding a live socket until the 30 s cleaner, and entries are never deleted (`dialer.go:428-435`), so under repeated handovers you accumulate live QUIC connections on dead paths, each with its own goroutines. **Reject as anything but an emergency stopgap.**

**Refuting A's F2 (exact alarm + manifest receiver as restart engine).** Depends on `setExactAndAllowWhileIdle` quota (~1 per 9–10 min in deep Doze), on `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` being grantable (on Android 13 `SCHEDULE_EXACT_ALARM` is user-revocable and Play policy restricts `USE_EXACT_ALARM` to alarm-clock/calendar apps — **a VPN app declaring `USE_EXACT_ALARM` is a plausible Play rejection**), and on the temporary-allowlist-from-alarm exemption still applying on 15/16. Three unverified dependencies stacked. It is a reasonable *last-resort tier*, not the primary engine. And it is only needed at all if we keep killing the process — which the converged design does not.

**Refuting A's F1/F3 framing that the patch "makes P1/P2/P4/P5/P6/P11 moot".** Partially. It removes the need for a process restart *for pool reasons*. It does not remove the need for the `:vpn` process to survive OEM kills, nor the need for a boot/always-on path. A over-claims.

**Refuting C's F2 (keep one tun for the session).** Sound, but note the interaction with `setTunFd`: the env var `xray.tun.fd` persists for process lifetime (`xray/xray.go:51-53`, read lazily at `tun_android.go:28`), so a redial path that forgets to `setTunFd(freshDup)` before `start()` will silently reuse a **stale fd number** — and with C's "leak the dup, don't close it" advice, that stale number is still open, so it fails *silently* rather than erroring. This is a footgun the redial path must assert against (log the fd, and never reuse).

**Refuting C's F4 (add IPv6).** Correct as a leak fix, but I cannot confirm `proxy/tun` + the hysteria outbound handle v6 destinations in this build. Add the `::/0` **route** (fail closed) before adding the address. If the address is added and v6 is broken end-to-end, you convert a leak into a total outage for v6-preferring apps.

**Proposals relying on banned foundations — explicit call-out:**
- Anything routing recovery through `VpnStatusBroadcast.sendRecoverRequest` / `PlatformVpnController.onRecoverRequested` → **relies on the main process being alive.** Refuted by A's log evidence (C8). Delete from the critical path.
- Any reliance on `START_STICKY` self-heal (`OnthecrowVpnService.kt:136-149`) → zero field occurrences across all logs. Treat as non-existent.
- `finalmask.quicParams` → **unverified config field**; must not be load-bearing (above).

---

## 3. GAPS — what all three of us missed

1. **`registerProtectControllers` is worse than "N redundant protect calls".** Verified this round: each `start()` creates two *new* `Proxy` objects and appends them (`PlatformXrayEngine.android.kt:103, 168-186`). After N in-process restarts every UDP socket makes 2N JNI round-trips inside `lc.Control`, i.e. **inside the socket-creation hot path, before bind**. At N=10 that's 20 Binder-adjacent calls per dial. Worse: if any handler returns `false`, xray's controller loop may treat the socket as unprotected — and an **unprotected upstream socket routes back into the tun**, producing an encapsulation loop that looks exactly like "tunnel dead" while burning CPU. Nobody flagged the loop failure mode. Must be an `AtomicBoolean` guard before any in-process restart ships.

2. **Server-address DNS resolution during recovery.** If the outbound address is ever a hostname, the fresh dial has to resolve it. Resolution inside the :vpn process goes through whatever resolver xray is configured with — and if that path traverses the (dead) tunnel, the redial **hangs instead of failing fast**, and no probe/timer notices. Nobody covered the resolve step. Mitigation: pin the outbound to an IP literal, or ensure a `freedom`/direct DNS outbound with a hard timeout.

3. **Panic = process death.** A Go panic anywhere in xray (nil-deref in `CloseAll`, gVisor reader on a recycled fd, a JNI exception escaping) kills `:vpn` outright — and we established START_STICKY does not bring it back. Every in-process change we are proposing *increases* the number of times we execute the risky code paths. There is no crash-recovery story in any of the three reports. This argues for keeping one process-restart tier alive as a genuine backstop, driven by something that survives process death.

4. **The pool's config capture (`dialer.go:449-465`) also captures `socketConfig`.** I noted the auth-string bug; the same freeze applies to the socket config used for future sockets on that client. On a key hit nothing is refreshed. Another reason in-process reuse must be paired with a real flush rather than "wait for the timer".

5. **`onLost` → no `applyUnderlyingNetworks(null)`, plus the null-then-reavailable false "change"** — C and A both half-covered; the combined effect is that a Wi-Fi flap triggers a *full* recovery, and under the current design that is a 22 s unprotected-traffic window for a network that never changed. That's a self-inflicted outage.

6. **Nobody measured the cost of a correct redial end-to-end.** Two data points exist: 272 ms and 190 ms from `dialing to udp:` → `congestion bbr`. That is the entire budget of the fix. Every second beyond ~500 ms in the converged design is coordination overhead we chose to add.

---

## 4. CONVERGED RECOMMENDATION

Design principle: **the `:vpn` process repairs itself, in-process, without touching the tun, without the main process, without a process kill, and without trusting any timer that freezes across suspend.** Process restart survives only as tier 3.

### Phase 0 — instrumentation (do these before anything else; ~30 min, unblocks three disputes)
1. `OnthecrowVpnService.kt:462-474` — log `socket.localAddress` on every probe. Settles C5.
2. `OnthecrowVpnService.kt:245` — log the full `runtimeJson` once. Settles whether `finalmask.quicParams` exists and whether §3.1 is an insert or a merge.
3. Log the dup'd fd number on every `setTunFd` and every close. Settles the recycling race.

### Phase 1 — make the sensor honest (highest value/effort; fixes the "recovery stops one step short" failure)
4. `OnthecrowVpnService.kt:462-474` — rewrite `probeTunnel` per C's F1: `connect()` the DatagramSocket, **assert `localAddress == 10.77.0.2`**, check the DNS txid and the QR bit. Anything else is a hard fail.

### Phase 2 — make recovery in-process and tun-preserving
5. `PlatformXrayEngine.android.kt:103` — guard `registerProtectControllers` with a process-level `AtomicBoolean`. **Blocking prerequisite for everything below** (gap 1).
6. `OnthecrowVpnService.kt:299-304` — stop closing the dup'd fd. Leak it; process death reclaims it. Always `setTunFd(freshDup)` before every `start()` and log the number (footgun in §2).
7. `OnthecrowVpnService.kt` — add `runRedial()`: `stopXray()` → `CloseAll()` (phase 3) → `setTunFd(freshDup)` → `xrayEngine.start()`. **Never calls `stopTunnel()`.** `runConnect`/`stopTunnel` remain for user connect/disconnect and fatal failure only (C6).
8. Split `runDisconnect` (`:306-326`) so only the user/revoke path calls `paramsStore.clear()` (`:311`). Recovery keeps the persisted config. Move `recordRecoveryKill()` (`:410`) to *after* a confirmed-successful recovery.
9. Delete `sendRecoverRequest`/`registerRecoverRequest` (`VpnStatusBroadcast.kt:45-59`) from the critical path and `onRecoverRequested`/`reconnect` (`PlatformVpnController.android.kt:47-77`) as the primary mechanism. Main process becomes a UI mirror only.

### Phase 3 — the deterministic pool flush (this is the actual fix)
10. Fork xray-core; add to `transport/internet/hysteria/dialer.go`:
```go
func CloseAll() {
    manger.mutex.Lock()
    clients := make([]*client, 0, len(manger.m))
    for k, c := range manger.m { clients = append(clients, c); delete(manger.m, k) }
    manger.mutex.Unlock()
    for _, c := range clients { c.closeLocked() } // takes c's own mutex; idempotent
}
```
Take the **client** lock, make `close()` idempotent (gap: nil-deref panic = process death, §2). Do the same for `transport/internet/quic`'s `clientConnections` global if vless-over-QUIC is in scope. Export through a libXray wrapper next to `onthecrow_convert/`; add the `replace` in `.libxray-build/libXray/go.mod`; call from `PlatformXrayEngine.stop()` immediately after `stopXray` returns.

### Phase 4 — triggers (act regardless of screen state)
11. `OnthecrowVpnService.kt:591-645`:
    - trigger on `onCapabilitiesChanged` + `NET_CAPABILITY_VALIDATED` on a network `!= lastUnderlying`, not bare `onAvailable`;
    - trigger on `onLinkPropertiesChanged` for the current underlying (same-netId route change);
    - on `onLost`, call `applyUnderlyingNetworks(null)` and **do not** null `lastUnderlying` — compare netIds so a flap is not a "change";
    - remove the `screenOn` gate at `:609-613`.
12. Add an `ACTION_DEVICE_IDLE_MODE_CHANGED` receiver alongside the screen receiver (`:508-533`); on idle-exit run refresh + probe + redial.
13. Restore `refreshUnderlyingFromSystem()` (scan `cm.allNetworks` for `NOT_VPN+INTERNET`, prefer `VALIDATED`) and call it at the top of every recovery — for the *detection* reason (C3), not routing.
14. Register the network callback **before** `startXrayOnTun` (`:203-207`).
15. `WAKE_LOCK` permission + a `PARTIAL_WAKE_LOCK` held for the duration of a recovery attempt only, 20 s timeout, released in `finally`. Without it the redial can be suspended mid-flight.
16. Serialize the tier ladder behind a `Mutex` + `NonCancellable` critical section so duplicate `onAvailable` can't cancel an in-flight redial.

### Phase 5 — defence in depth (ship, but nothing depends on it)
17. `XrayConfigSanitizer` — **merge** (never overwrite) `streamSettings.finalmask.quicParams: { maxIdleTimeout: 8, keepAlivePeriod: 3 }`. Preserve `congestion`/`brutalUp`/`brutalDown`/`udpHop`. Gated on Phase 0 step 2. Helps only the screen-on handover case.
18. IPv6: add `::/0` **route** first (fail closed); add the address only after verifying v6 through the tun inbound and hysteria outbound.
19. In-app recommendation for **always-on VPN + lockdown** (deep-link `Settings.ACTION_VPN_SETTINGS`) + HyperOS autostart prompt. This is the only real answer to gap 3 (Go panic / OEM kill): the system restarts the VpnService and fails closed meanwhile.
20. Keep the process-kill path as **tier 3 only**, and only for user-initiated config switches — which is also correct given the pool's config-capture bug (`dialer.go:449-465` reuses the old auth on a key hit).

### Fallback chain at runtime
`T0` refresh underlying + probe (honest) → if dead, `T1` `runRedial()` (stopXray → CloseAll → setTunFd → start), re-probe at 500 ms/1 s/2 s → if still dead after 2 attempts, `T2` full tun rebuild + redial → if still dead, `T3` persist params, kill `:vpn`, restart via alarm-backed manifest receiver.

### Expected numbers
- **T1 redial cost: ~250–400 ms** (measured fresh dial 190–272 ms + stop/start overhead). Post-handover recovery: **< 1 s**, screen on or off.
- **Post-Doze:** bounded by trigger latency (idle-mode-exit or first `onCapabilitiesChanged`), then < 1 s. No 30 s stall — `CloseAll()` removes the dependency on a frozen timer entirely.
- **Reliability:** T0–T2 touch no FGS restriction, no broadcast delivery, no `START_STICKY`, no main process. I'd expect ~100 % *conditional on the `:vpn` process being alive*.

### What could still break it
- `:vpn` killed by OEM/LMK or a Go panic → only always-on VPN (19) or the T3 alarm path recovers it. This is the residual risk and it is not fully closable in app code.
- `CloseAll()` racing an in-flight `c.tcp()`/`c.udp()` → panic → process death. Mitigated by client-level locking + idempotent close; **must be stress-tested** (redial in a loop under load).
- The probe's own DNS path being black-holed for a reason other than a dead tunnel (A's open Q7) → honest-probe assertion (step 4) gives false *negatives* instead of false positives, causing needless redials. At 250 ms each, acceptable.
- Non-hysteria transports keeping their own connection globals (gap: `transport/internet/quic`).

---

### Systems / network

# GROOMING NOTE — Systems / Network lens (Report C author)

Everything below marked **[V]** was re-verified against the working tree, the log files, or `xray-core@v1.260327.0` in the module cache during this session. **[I]** = inference.

---

## 0. New evidence found during grooming (changes the ranking)

**The probe false-positive is now proven by physics, not just by log correlation. [V]**

`grep` over `vpn-debug (13).log` for every `tunnel probe OK`:

| time | latency | preceding `tun established` |
|---|---|---|
| 00:45:30.281 | **226 ms** | 00:45:30.005 (276 ms earlier) |
| 00:46:11.166 | **158 ms** | 00:46:10.916 (250 ms earlier) |
| 00:46:23.039 | **38 ms** | 00:46:22.904 (**135 ms** earlier) |
| 01:39:39.484 | **39 ms** | 01:39:39.411 (**73 ms** earlier) |
| 01:40:03.687 | **38 ms** | 01:40:03.616 (**71 ms** earlier) |

The server is `78.17.84.51` (Ireland). A DNS query that genuinely traverses tun → gVisor → hysteria2 → Ireland → 1.1.1.1 → back **cannot** complete in 38 ms; the honest probes cost 158–226 ms. The three 38–39 ms probes are the device talking to a local resolver/anycast node **outside the tunnel**. And all three occurred 71–135 ms after `establish()` returned, versus 250–276 ms for the honest ones.

This upgrades my P1 from "correlation" to "two independent proofs" and it settles the ranking: **the recovery machinery has been repeatedly told it succeeded when it had not.** Any latency measurement, any "did the fix work" verdict, and any keepalive decision taken so far is built on a sensor that lies precisely in the state it exists to detect. **Fix the probe first or you cannot evaluate any other change.**

Also re-verified this session, all in `OnthecrowVpnService.kt`: the class comment at `:290` (`"The tun interface stays up"`) and `:60-63` are both **false** — `runConnect:188` calls `stopTunnel()` which closes `tunInterface` at `:343`. `onLinkPropertiesChanged` is log-only. `onLost` does not call `applyUnderlyingNetworks(null)`. `probeTunnel` uses an unconnected `DatagramSocket`, checks only `length > 0`, never checks the txid it builds. Manifest: **no** `WAKE_LOCK`, **no** `RECEIVE_BOOT_COMPLETED`, **no** `SCHEDULE_EXACT_ALARM`, **no** manifest receiver of any kind. `XrayConfigSanitizer.withTunInbound` touches only `inbounds`, `log`, and `outbounds.sendThrough` — it never emits `streamSettings`.

---

## 1. CONTRADICTIONS

### C1 — "The tun is kept open across recovery" (A, implicitly; the code's own comments) vs. "the tun is rebuilt every time" (C). **C is right. [V]**
`runConnect:188 → stopTunnel():340-346` closes the master fd on every path, including recovery. Report A's walkthrough repeats the stale comment. This matters because A's and B's leak/latency estimates are all too optimistic.

### C2 — Cause of the ~30 s stall: "stale pool, evicted by an internal idle timeout" (brief, fact #3) vs. "quic-go `MaxIdleTimeout=30 s`, keepalive disabled" (B) vs. "same, but counted from CPU resume because `CLOCK_MONOTONIC` freezes in suspend" (C). **B's mechanism is right; C's refinement is right and B's is incomplete. [V] for the mechanism, [I] for the clock detail.**
Verified in `dialer.go`: `status()` (`:129-139`) is a bare `select` on `c.conn.Context().Done()`; `dial()` (`:149-156`) returns `nil` while `StatusActive`; `MaxIdleTimeout` defaults to 30 s (`:245-247`); `KeepAlivePeriod` default is **commented out** (`:248-250`) so keepalive is off; `DisablePathManager: true` unconditionally (`:231`). The clock claim rests on the single 30.08 s measurement from screen-on — flagged as not fully adjudicable, see §1-U1.

### C3 — Is the pool "un-clearable without a process restart"? Brief says yes; A calls it unverified; B and C say it is clearable **only** with a Go patch. **B/C are right. [V]**
`manger` is a package-level unexported var (`dialer.go:437`), `clean()` unexported, the package exports only `Dial`/`Listen`. Nothing reachable from JSON or from a dialer controller touches it.

### C4 — A's F4 rationale: restore `refreshUnderlyingFromSystem()` because it "makes protected sockets follow the network." **Wrong. [V] from AOSP semantics.**
`setUnderlyingNetworks()` writes `NetworkAgent` metadata only — transports, metered/roaming/suspended/congested, bandwidth, `NetworkStats` attribution, and what apps behind the VPN see. It has **zero** effect on any socket's fwmark or route lookup. `protect()`ed sockets follow the **system default network**, always. Restore the re-query — but the justification is (a) correct capability advertisement and (b) it is the app's only way to *notice* a handover it slept through (`01:39:34.280 underlying refresh: STALE 111 -> 101` and two more identical lines at `00:46:22.874` and `01:11:20.311`, all on transitions where `onAvailable` never fired). The service's own doc comment at `:573-577` states the wrong model and should be corrected in the same commit, or the next person re-derives the wrong design.

### C5 — B's §3.1 sold as "makes the pool self-heal, no process kill needed." **Overstated. [V] + [I].**
Two independent problems. (i) Timers do not run in suspend, so post-Doze it yields "5 s after wake", not "5 s after the link died" — and a 2 s keepalive cannot hold a carrier NAT mapping open through deep sleep, because the ping never fires. (ii) **New this session [V]:** `dialer.go:448-461` captures `quicParams: streamSettings.QuicParams` **only on pool insert**. An existing entry keeps the params it was born with. So injecting `quicParams` is a no-op for any already-pooled address until that entry is discarded — which is the thing it was supposed to cause. It works on a fresh process; it does not retroactively shorten the timer on the corpse you are currently stuck behind. Ship it as defence in depth, not as the fix.

### C6 — B's P5 ("wrong-credential bug on pool hit"). **Confirmed and it is worse than stated. [V].**
`dialer.go:449-465`: on a key hit only `c.setCtx(ctx)` runs. `config` (auth), `tlsConfig`, `socketConfig` **and** `quicParams` are all frozen at first insert. Any in-process design must never use an in-process restart to switch config on the same `host:port`.

### C7 — A's P6/F2 on START_STICKY and the alarm restart engine. Adjudicated adversarially in §2. Short version: A's diagnosis (START_STICKY is dead) is right; A's replacement is not safe.

### Cannot adjudicate (out of my lens / no data)
- **U1** — whether `CLOCK_MONOTONIC` suspend-freeze fully explains the 30.08 s. One observation, striking match. Settled by the experiment in §4.
- **U2** — whether the main process is actually dead at recovery time (A's P1 vs. B's own caveat). A's log citation shows a ~35 min main-process gap at `01:40:42 → 02:16:00` and 39–68 s broadcast deferrals delivered as one batch at `01:40:42.460-462`; both are real. Whether that is LMK, HyperOS, or cached-app deferral I cannot separate. It does not change the design: the fix is the same either way.
- **U3** — whether the FGS-from-background exemption via the device-idle allowlist holds on HyperOS.
- **U4** — server-advertised `max_idle_timeout`. No visibility.

---

## 2. ADVERSARIAL CHECK — every major proposal, tried to break

**A/F2 — alarm + manifest receiver as the restart engine. REJECT as primary. Keep as tier-4 only.**
Breaks in at least four ways. (i) `setExactAndAllowWhileIdle` is rate-limited to ~1 per 9–10 min per app in deep Doze — a handover storm gets one attempt every ten minutes, which is the opposite of the reliability goal. (ii) It reintroduces the 22 s no-VPN gap I measured (`02:16:09.861` kill → `02:16:32.109` new tun), during which **every app on the device egresses in the clear** — a VPN that leaks for 22 s on a recovery the user never asked for is a worse outcome than a dead tunnel. (iii) A receiver in `:vpn` fired by an alarm does **not** inherit the FGS-start exemption of a process that no longer exists; it relies on the temporary power allowlist granted by the exact-alarm broadcast, and A itself admits this is not on the public exemption list and is user-declinable. (iv) OEM: HyperOS's autostart restriction gates exactly this manifest-receiver cold-start path. Four dependencies, each a coin flip, multiplied.

**A/F1 — "delete the cross-process broadcast, drive recovery in `:vpn`." ACCEPT, and it is the right instinct.** But note it is only *achievable* if recovery no longer requires process death — i.e. it is downstream of the pool fix, not independent of it. A proposes it while also keeping process-kill as the mechanism, which is internally inconsistent.

**A/F7 + C/F6 — always-on VPN + lockdown. ACCEPT as a recommendation, REJECT as a mechanism.** It cannot be granted programmatically; it is a user toggle in system Settings. It is genuinely the strongest thing available (the system owns restart, and lockdown installs UID-range blackhole rules so every residual gap fails **closed** instead of leaking) — but you cannot ship reliability that depends on a setting most users will not enable. Deep-link to `Settings.ACTION_VPN_SETTINGS`, do not build on it.

**B/§3.1 — `finalmask.quicParams: {maxIdleTimeout: 5, keepAlivePeriod: 2}`. ACCEPT with reduced expectations, and one unverified-field caveat that must be closed before shipping.** Refutations: the pool-insert capture (C5-ii) means it cannot rescue an entry already stuck; timers freeze in suspend so it does nothing for the Doze case; and a >5 s gap on a bad cell tears down a healthy connection. The JSON path (`streamSettings.finalmask.quicParams`, bounds `maxIdleTimeout ∈ [4,120]`, `keepAlivePeriod ∈ [2,60]`) is read from `infra/conf/transport_internet.go` and I have **not** round-tripped it through a real config — **treat it as unverified until you log the generated `runtimeJson` and confirm the field survives parsing.** Merge, never overwrite: `share/hysteria_mask.go` only allocates `quicParams` when the link carries bandwidth/ports, and clobbering it silently downgrades brutal → BBR.

**B/§3.2 Tier 0 ("set underlying, wait, re-probe"). REJECT.** It rests on C4's wrong model — `setUnderlyingNetworks` routes nothing — so Tier 0 is literally "wait for the idle timer", i.e. up to 30 s today or 5 s with §3.1, and unbounded in suspend. It is not a recovery tier, it is a delay.

**B/§3.2 Tier 1 (`stopXray → setTunFd → start`, keep tun). ACCEPT — but it is a **no-op without the pool fix**, and the logs prove it.** `xray (3).log` shows three in-process restarts at `20:39:34.338`, `20:39:39.439`, `20:39:58.567` with **no** `dialing to udp:` between them; the first fresh dial is at `20:40:02.834`. Restarting the instance does not touch `manger`. Sequencing is therefore mandatory: pool fix **before** in-process restart, or you ship three futile restarts per recovery again.

**C/F3(b) — pool key rotation (hostname ↔ IP literal, or alternate ports). WEAK, and I am downgrading my own proposal.** New this session [V]: `dialer.go:449` computes the key from `dest.NetAddr()` **before** udpHop port selection, and udpHop then randomises the dial port *within* one entry — so `udpHop.ports` does **not** mint a new key. Only changing the configured `address`/`port` string does. That fails outright when the config carries a bare IP and one port, which is the common case for these share links. Stopgap only.

**Anything relying on START_STICKY. REJECT outright.** Zero `"sticky restart"` hits across every `vpn-debug*.log` [V]. It has never once fired.

**Anything relying on the main process. REJECT outright.** Runtime-registered receiver (`PlatformVpnController.android.kt:42-44`) cannot start a process; observed 39–68 s deferrals when it *is* alive; `lastXrayJson` is in-memory only. Three independent failure modes on the critical path.

---

## 3. GAPS — what all three of us missed

**G1 — Nobody counted the leak on the *failure* path, and nobody proposed failing closed.** Every recovery design that closes the tun (all of them, today) exposes every app on the device in the clear for the duration. Measured: 22.2 s. On a *failed* recovery it is unbounded. For a VPN this is a security defect, not a latency defect, and it is the strongest argument for the keep-the-tun design independent of any reliability argument.

**G2 — DNS.** The tun advertises `1.1.1.1` as its only resolver and `com.google.android.gms` is split-tunnelled out. Nobody checked whether Android's resolver, private-DNS (DoT to `dns.google`, on by default as "Automatic" on many devices), or the GMS exclusion is producing the plain-38-ms answers independently of the establish-window race. **This is a live alternative explanation for the false positives and must be checked in the same experiment.** If Private DNS is on, the resolver traffic is DoT on 853 from the resolver UID and does not traverse our probe path at all.

**G3 — The probe measures the tunnel, but the *tunnel* is not what recovers.** Even a correct probe cannot distinguish "upstream dead" from "server down" from "credential rejected". A recovery ladder that treats all three identically will hot-loop against a genuinely-down server. Needs an attempt budget with backoff and a terminal `Error` state surfaced to the user.

**G4 — IPv6 (my P6) is a reliability trap, not just a leak.** With no `::/0` route, IPv6-capable apps keep working over the physical link while the tunnel is dead. The user does not notice, does not report, and the IPv4 probe is the only thing that knows. This is why "it mostly works" and "it is completely broken" have coexisted in the field reports.

**G5 — VPN revocation.** `onRevoke()` is not in any report. Another VPN app (or the user in Settings) revoking us mid-recovery produces a tun that is gone but a service that thinks it owns one. Must be handled distinctly from a network failure — it is a terminal state, not a recoverable one.

**G6 — Multiple concurrent recovery drivers.** Screen-on, network-change, keepalive and (proposed) idle-exit all call `startTunnelJob`, which cancels the in-flight job (`:372-373`). During a handover `onAvailable`/`onLost` fire in pairs (`02:16:09.625/.705`). Today `recover()` is a fire-and-forget so cancellation is harmless; the moment recovery does real work, a duplicate callback silently kills a restart mid-flight. Needs a single-permit mutex with the critical section `NonCancellable`.

**G7 — MTU 1500 on the tun.** Proxied UDP near 1500 B cannot fit a QUIC DATAGRAM frame (quic-go clamps ~1200–1350). QUIC-over-QUIC (browser HTTP/3, all over the logs as `udp:…:443`) and WebRTC are exposed. 1400 is the conventional value. Low priority, unconfirmed without a capture.

---

## 4. CONVERGED RECOMMENDATION — what I would ship

Strictly ordered. **Steps 0–2 are the fix; nothing after step 2 is measurable until step 0 lands.**

**Step 0 — Make the sensor honest.** `OnthecrowVpnService.kt:462-474`. `DatagramSocket.connect(1.1.1.1:53)` first (forces the kernel to bind a source address from the route it will actually use, and filters replies by 5-tuple), then **assert `socket.localAddress == 10.77.0.2`** — extract the address to a constant shared with the `Builder` at `:193`. Verify the DNS txid and the QR bit in the response. Log `localAddress` on every probe for one build. This alone would have caught all three false positives, and it is the only way to trust any later measurement. Cost: ~15 lines.

**Step 1 — Fork libXray, export a pool flush.** `.libxray-build/libXray` is present with full source [V]; `scripts/build-libxray-android.sh` already builds xray from source and the repo already carries a custom Go package (`onthecrow_convert/`), so the machinery exists. Add to a fork of `transport/internet/hysteria/dialer.go`:
```go
func CloseAll() {
    manger.mutex.Lock(); defer manger.mutex.Unlock()
    for k, c := range manger.m { c.close(); delete(manger.m, k) }
}
```
Expose it via a libXray wrapper; `replace github.com/xtls/xray-core => <fork>` in `.libxray-build/libXray/go.mod`; call it from `PlatformXrayEngine.android.kt` `stop()` immediately after `stopXray`. This clears the frozen `config`/`quicParams`/`tlsConfig` capture (C5-ii, C6) as well as the connection. **This is the load-bearing change** — it is the only thing that makes recovery independent of a timer that does not run in suspend, and it is what turns process death from a requirement into a fallback.

**Step 2 — Keep the tun; re-dial only xray.** Split `runConnect` so recovery takes `runRedial()`: `stopXray()` → `CloseAll()` → `setTunFd(freshDup)` → `xrayEngine.start()`, **never touching `tunInterface`**. `stopTunnel()` stays for user disconnect / revoke / fatal only. Do **not** close the dup'd fd (`:302`) — `AndroidTun.Close()` is a verified no-op and `Instance.Close()` does not join the gVisor readers, and the log shows fd numbers recycling deterministically (`102/103` every cycle); leak one fd per restart, bounded by recovery count, reclaimed at process death. Make `registerProtectControllers` idempotent (`PlatformXrayEngine.android.kt:103`) — `internet.RegisterDialerController` appends to a process-global slice iterated per socket. This removes: the 22 s leak (G1), the establish-window false positive (Step 0's root cause), the fd race, and the destruction of every app's sockets on every recovery. Expected recovery cost: **~250 ms** (measured fresh dials: 272 ms and 190 ms).

**Step 3 — Recovery lives entirely in `:vpn`.** Delete `sendRecoverRequest`/`registerRecoverRequest` (`VpnStatusBroadcast.kt:45-59`) and `onRecoverRequested`/`reconnect` (`PlatformVpnController.android.kt:47-77`). Ladder, single mutex, critical section `NonCancellable` (G6): **T1** `runRedial()` → probe → done. **T2** on failure, `refreshUnderlyingFromSystem()` + `runRedial()` again. **T3** two T2 failures → full `stopTunnel()` + `runConnect()` in-process. **T4** last resort only → the existing process-kill path, which is also the **only** legal path for a user-initiated config switch (C6). Hold a `PARTIAL_WAKE_LOCK` (add the permission) with a 20 s timeout across the whole ladder, released in `finally`. Attempt budget with backoff and a terminal error state (G3). Split `runDisconnect` so only user/revoke calls `paramsStore.clear()` (`:311`) — recovery must never destroy its own fallback.

**Step 4 — Trigger set.** Recover on: `onCapabilitiesChanged` where `NET_CAPABILITY_VALIDATED` turns true on a network `!= lastUnderlying` (not bare `onAvailable` — cellular validates 1–3 s late and a dial before that burns an attempt); `onLinkPropertiesChanged` when addresses/routes change on the current underlying (the only signal for a same-netId change); `ACTION_DEVICE_IDLE_MODE_CHANGED` exit; `SCREEN_ON`; keepalive failure. `onLost(current)` → `applyUnderlyingNetworks(null)`. Track the last *available* network rather than nulling on `onLost`, so a Wi-Fi flap does not read as a change. Restore `refreshUnderlyingFromSystem()` (scan `allNetworks` for `NOT_VPN+INTERNET`, prefer `VALIDATED`; `activeNetwork` is useless — we are the VPN) and run it at the top of every recovery. Fix the false doc comments at `:573-577`, `:60-63`, `:290`. Register the callback **before** `startXrayOnTun`. **Stop gating on screen-on**: post-step-2 a handover costs ~250 ms of CPU. Keep the 8 s keepalive screen-on only, drop to ~60 s screen-off (best-effort — `delay()` does not fire in suspend, and that is fine; the goal is bounding the dead window, not eliminating it). Probe timeout down to 600 ms.

**Step 5 — Fail closed.** Add `addAddress("fd00:1:2:3::1",128)` + `addRoute("::",0)` + a v6 DNS server (`:190-201`), after verifying the tun inbound and hysteria outbound handle v6 destinations — if they cannot, add the route anyway so v6 fails closed rather than leaking (G4). Handle `onRevoke()` as terminal (G5). Recommend always-on + lockdown in-app via `Settings.ACTION_VPN_SETTINGS`. MTU 1400 (G7), low priority.

**Ship `finalmask.quicParams {maxIdleTimeout: 5, keepAlivePeriod: 2}` alongside**, merged not overwritten, as defence in depth for the screen-on case — after confirming the field actually survives config parsing.

### Expected outcome
- **Wi-Fi ↔ cell, screen on:** ~100%, ~300–500 ms (validated-trigger delay + ~250 ms re-dial). No leak.
- **Wi-Fi ↔ cell, screen off:** ~100% recovered by the time it matters; the handover itself is handled if the callback is delivered, otherwise caught at idle-exit or screen-on within ~1 s.
- **Doze exit:** ~100%, ~600 ms (honest probe) + ~250 ms (re-dial), bounded by the ~60 s screen-off probe as backstop.
- **No dependency on:** START_STICKY, the main process, broadcast delivery, exact-alarm quota, FGS background-start rules, the battery-optimisation dialog, or OEM autostart. That is the point of the design — every one of those is a coin flip and today they are multiplied together.

### What could still break it
1. **Step 1 blocked** (no appetite for a fork). Then the whole design degrades to "wait out a 5 s timer that does not run in suspend", and the process-kill path returns as the primary — with all of A's Doze-restart problems and the 22 s leak. This is the single decision that determines whether the target is reachable.
2. **OEM kills the `:vpn` FGS itself.** Nothing app-side beats HyperOS here; only always-on VPN does, and it is a user setting.
3. **G2 turns out to be the real cause of the 38 ms probes** (Private DNS / GMS exclusion rather than the establish window). Step 0's `localAddress` assertion is correct either way, but the diagnosis in my P1 would need revising.
4. **The pool flush does not cover a second cached layer** I have not audited — `http3.Transport` holds its own connection state, and I have not proven `c.close()` tears it down completely.

### First experiment, before writing any other code
Land Step 0's `localAddress` logging alone, reproduce the Doze-exit sequence, and read three numbers: the probe's local address on a "healthy" verdict, the delay from screen-on to the next `dialing to udp:` in `xray.log`, and whether `am kill` of the main process changes anything. That settles U1, U2, my P1's mechanism, and G2 in one run — and it costs about fifteen lines.
