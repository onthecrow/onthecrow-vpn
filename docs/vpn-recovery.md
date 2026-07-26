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
                          ├─ T0: probe, patiently (up to 45s awake)
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

## Config and split-tunnel changes

Changing the selected config **or the split-tunnel routing** while connected restarts the tunnel to
apply it (`VpnSyncWorker.restartWith`). Validation happens **before** the disconnect — validating after
the teardown raced the just-killed engine process and left the tunnel down until a manual reconnect. A
bad new config therefore never costs the working tunnel.

---

## What is still open (needs a device)

Listed with exact measurements in the "Нерешённое" section of
[`vpn-architecture-audit.md`](./vpn-architecture-audit.md): whether the tun rebuild actually helps a
given app, the precise leak-window duration, in-Doze callback delivery, and the battery cost of the 3s
keepalive.
