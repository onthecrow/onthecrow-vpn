# VPN recovery: network changes and Doze

How the tunnel survives a Wi-Fi ↔ cellular switch and a wake from deep sleep, and why it is built this
way. For the full internal reference see [`.claude/vpn-service.md`](../.claude/vpn-service.md); for the
audit that shaped this, [`vpn-architecture-audit.md`](./vpn-architecture-audit.md).

Priority order: **reliability first, then latency.** The tunnel must recover from any network change or
Doze exit indefinitely, without the user touching anything.

---

## The shape of the problem

Outbound is hysteria2 over QUIC. Everything the tunnel carries is multiplexed onto **one QUIC session**,
so any path change destroys every tunnelled connection at once. Recovery has to do two separate things,
and confusing them cost us weeks:

1. **Make the tunnel carry traffic again** — re-dial the upstream over the new path.
2. **Tell the apps behind the VPN that the network is back** — some apps wait for a *signal*, not for a
   working tunnel.

The health probe answers (1). It cannot answer (2), because it opens a *new* connection and new
connections work the instant the engine re-dials.

---

## Two processes

- **App process** owns the tun for the whole session and never dies on purpose. It runs the recovery
  ladder, the probe, and the network callbacks.
- **`:xray` process** hosts libXray alone and exists to be killed and replaced. hysteria2 keeps a
  process-global connection pool that `stopXray` never reaps (nothing is deleted from its client map,
  and a keepalive'd connection never goes idle), so only process death gives a genuinely fresh engine.

Killing `:xray` leaves the tun untouched, so the status-bar VPN icon does not blink — the thing the
old design did on every recovery.

---

## The recovery ladder

```
network/Doze trigger ──► recover()
                          │
                          ├─ no usable underlying network?  → stand down (a network appearing re-triggers)
                          ├─ device slept > 20s mid-ladder? → abandon (evidence is stale)
                          │
                          ├─ T0: probe, patiently (up to 45s awake)      ← tunable, see below
                          │      timeout grows 600 → 1200 → 2500 → 4000 ms
                          │      first success → healthy
                          │                    → if the path MOVED: rebuild the tun (see below)
                          │
                          └─ T2: kill & respawn :xray, backoff 1s…5min, uncapped
```

There is no middle rung. An in-process re-dial ("T1") used to sit here; it was deleted — it never
succeeded in the field and could not clear the state a restart exists to clear.

### Why T0 is patient (45s)

Every probe failure ever recorded was a **timeout**, never an error. "No answer" therefore cannot
distinguish a broken path from one that has not finished coming up. An early version condemned the
tunnel after 1.9s and produced a repair loop that killed the process ~37 times a day on healthy
tunnels. The patience is sized to the thing we are actually waiting for: hysteria2's QUIC idle timeout.

The budget is spent in **awake** time, not wall-clock — measured as `elapsedRealtime − uptimeMillis`.
The QUIC timer is also `CLOCK_MONOTONIC` and stops during suspend, so budgeting on wall time would burn
the whole budget on one Doze nap without ever asking the tunnel.

### "Aggressive keepalive" — the opt-in impatient T0

**Settings → Reliability → Aggressive keepalive**, off by default. With it **off, the ladder above is
exactly what runs** — same 45s patience, same growing timeouts, nothing about the default path changed.

Turned on, T0 goes back to what it was before the `:xray` split: **two 600ms probes, 700ms apart, then
condemn** (a 1.9s awake budget, sized to admit exactly those two probes and no third). Everything else is
identical to the default ladder — both guards, the
Doze INCONCLUSIVE handling, and the rung it escalates into. In particular it escalates into **T2, the
`:xray` restart**, not the old "kill `:vpn` and let an alarm resurrect it": the tun stays open and the
VPN icon still does not blink.

The trade is the one the section above describes. It reacts faster when a path is genuinely gone, and
condemns healthy-but-slow tunnels that patience would have recovered for free — a cold LTE bearer was
measured at 23s between "cell appeared" and the first probe that could succeed. Useful for debugging or
on a network where paths die hard; not the right default.

The service collects `RecoveryTuningRepository` for its whole lifetime and each ladder reads the latest
value, so toggling it applies **live — no reconnect**. It is deliberately kept out of
`SplitTunnelSettings`, because everything in there is part of `VpnSyncWorker`'s `ConfigKey` and would
tear the tunnel down on every flip.

---

## Why recovery is fast: QUIC liveness

At xray's defaults a dead QUIC session is not abandoned for ~30s, and nothing re-dials until then — so a
handover or a Doze exit cost **19–35s** of dead tunnel. We set, per hysteria outbound:

- `maxIdleTimeout = 10s` — a dead session is dropped in ~10s instead of ~30s.
- `keepAlivePeriod = 3s` — what makes 10s safe. Without keepalives QUIC sends nothing on an idle
  connection and a *healthy* tunnel would time itself out.

Recovery is now typically **67–125ms** on a switch, because by the time the probe runs the session has
already re-dialled. These live in `XrayConfigSanitizer` and are merged into whatever the share link
already specified (bandwidth, port-hopping preserved).

**Battery.** The keepalive is a radio wakeup every 3s while the CPU is awake. It costs nothing in deep
sleep (the timer does not advance while suspended). This tradeoff is tracked in
[`android-doze-battery-plan.md`](./android-doze-battery-plan.md).

---

## Telling apps the network is back: the tun rebuild

On a **confirmed change of the underlying network**, after the tunnel recovers, the tun interface is
**rebuilt** — a fresh `establish()`, the engine restarted on the new descriptor, the old one closed.

Why: a path change keeps the *same* VPN network object, so apps behind the VPN only see a capabilities
change — never an `onAvailable`. Apps like Telegram wait for that signal and will sit in "waiting for
network" indefinitely over a provably healthy tunnel (measured: 12 minutes, while other apps worked
through the same tunnel). A fresh connect over the same network never had the problem, because then the
VPN network is new. The rebuild manufactures that new-network signal (a new netId, `onLost` then
`onAvailable`).

It fires on **every** confirmed move, not only when the tunnel was actually down — apps need the signal
because *their* network changed transport, which is independent of whether our tunnel stayed up.

**The cost, paid on each switch** (and only on a switch, which already broke every tunnelled
connection):
- ~600–850ms while the engine restarts.
- ~90ms in which the physical interface is the default network for our own traffic — a genuine, if
  brief, leak window (netd reinstalls per-app routing rules asynchronously after `establish()`).

Whether the rebuild is worth this on your device/apps is measurable — see the audit's open questions.

---

## Doze: no alarm

There is deliberately **no `AlarmManager` and no `SCHEDULE_EXACT_ALARM`**. Measured: an "exact" alarm
fired 14–15 minutes late in Doze, three times of three — the exemption buys nothing. Since the app
process now survives, there is nothing to resurrect. Recovery acts on the next wake: the idle-mode
receiver, screen-on, a network callback, or the keepalive. A path that dies during deep sleep is
noticed after the device wakes; the QUIC timer that governs re-dial does not advance while suspended
anyway, so nothing is lost by waiting.

---

## Every start must reach the foreground, fast

The service promotes itself with `startForeground()` as the very first thing `onStartCommand` does, on
**every** start — connect, disconnect, revoke, sticky restart.

Android starts a few-second deadline the moment anything calls `startForegroundService()`, and it does
not care what the intent was for: miss it and the **whole app process** is killed with
`ForegroundServiceDidNotStartInTimeException`, which is not catchable. Skipping the promotion for the
teardown actions was safe only because the stop path happens to use `startService()` — one call site
changing that would have made it a guaranteed crash.

The promotion is a no-op during a session (the service is already foreground), and the teardown is what
drops it: `runUserDisconnect` ends in `stopUnlessRestarted`, which stops the service and drops
foreground only if that stop actually took. It deliberately does **not** drop
foreground straight away in the disconnect branch — the tun is still up there.

The other half is **how the service stops**. Every teardown uses `stopSelf(startId)`, never a bare
`stopSelf()` — so a stop is skipped outright when a newer start has already arrived.

That protects any start landing during the slow part of a teardown — a Connect tap while `stopTunnel()`
is killing `:xray`, say. It was **not**, on its own, enough to fix the settings-change crash: the
teardown always reached ActivityManager before the reconnect it was supposed to lose to.

What fixed that is the section below.

Still open: `establish()` has no time limit, and a connect can be re-entered while a previous one is
still inside it. Our own in-flight `establish()` stalls the system, and `startForeground()` has to go
through the same place.

---

## Config and split-tunnel changes

Changing the selected config **or the split-tunnel routing** while connected rebuilds the tunnel to
apply it (`VpnSyncWorker.restartWith`). Per-app routing is fixed at `establish()` and cannot be changed
in place, so a new tun is genuinely required.

**The service is not stopped to do it.** `restartWith` sends one `connect()` straight into the running,
still-foreground service; `runConnect` opens with `stopTunnel()` and re-establishes from there.

It used to disconnect first and wait for `Disconnected`, and that is what crashed the app. The
disconnect announces `Disconnected` on the statement *before* it stops the service, and the reconnect it
releases resumes on a different dispatcher — so it could never reach ActivityManager in time to be seen
as a newer start. The service was destroyed every time, and the reconnect had to rebuild it from
`onCreate` while Android's foreground-service deadline, armed by that very reconnect, was already
running. Missing that deadline kills the whole app process, and nothing can catch it.

Connecting into a service that is already foreground has no such window: the deadline is satisfied by
the first statement of `onStartCommand`, and nothing stops the service at all. The user sees
Connected → Connecting → Connected instead of a trip through Disconnected.

Validation still happens **before** any of it — validating after the engine process was killed raced it
into a dead-object failure and left the tunnel down until a manual reconnect. A bad new config never
costs the working tunnel.

---

## Analytics

Recovery and lifecycle are instrumented with Firebase events (`vpn_connected`, `vpn_error`,
`vpn_session_end`, `vpn_recovery`, `vpn_engine_death`, `vpn_tun_rebuild`, `vpn_tunnel_confirmed`,
`vpn_keepalive_health`), fired from `DeltaVpnService` in the **main process only**. They carry
**outcomes, coarse buckets and enums** — a recovery run reports its trigger/outcome/attempt-bucket/
duration-bucket and the coarse transport type, never a network id, address, or timing. To make this
possible `runLadder` (and `restartEngineForRecovery`) now **return a `RecoveryOutcome?`** so `recover()`
can report one event per run; this changed no control flow. The full plan is in
[`analytics-events.md`](./analytics-events.md), and the per-hook map is section 16 of
[`.claude/vpn-service.md`](../.claude/vpn-service.md).

---

## What is still open (needs a device)

Listed with exact measurements in the "Нерешённое" section of
[`vpn-architecture-audit.md`](./vpn-architecture-audit.md): whether the tun rebuild actually helps a
given app, the precise leak-window duration, in-Doze callback delivery, and the battery cost of the 3s
keepalive.
