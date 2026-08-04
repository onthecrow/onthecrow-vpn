# VPN service — internal map for Claude Code

Everything about how the Android VPN runs, recovers, and talks to libXray. Read this before touching
anything under `core/vpn/impl/src/androidMain` or `core/xray/src/androidMain`. It is written to save you
from re-deriving facts that cost days of field debugging to establish.

**Keep this file current.** When you change VPN behaviour, update this doc AND
[`docs/vpn-recovery.md`](../docs/vpn-recovery.md) in the same change. The rule is in
[`CLAUDE.md`](../CLAUDE.md).

---

## 1. What it is

Android VPN client. Outbound is **hysteria2 over QUIC** via **libXray v26.7.11** (xray-core, gomobile/
JNI). Purpose: bypassing regional blocking (users in Russia — Telegram/Instagram/YouTube blocked
without it). **Not** an anonymity product, but traffic must not silently leak outside the tunnel. The
client only ever knows about Firestore (bundle existence); it knows nothing about xray servers.

Reliability outranks latency. A change that cannot be verified on a device is worth less than one that
can. Trust the user's empirical observations over any assumption.

---

## 2. Process topology (load-bearing — do not "simplify")

Two processes, one UID:

| Process | Holds | Lifetime |
|---|---|---|
| **app (default)** | `DeltaVpnService` (the `VpnService`), the tun fd, the recovery ladder, the health probe, ConnectivityManager callbacks, screen/idle receivers, the UI, Koin | Owns the tun for the whole session. **Never killed on purpose.** |
| **`:xray`** | libXray and nothing else, behind a hand-rolled Binder | Exists **so it can be SIGKILLed and replaced.** |

**Why `:xray` is a separate process (the real reason — earlier comments were wrong).** hysteria2 keeps
a package-level client-pool map (`transport/internet/hysteria/dialer.go`) from which **nothing is ever
deleted**, and its janitor only closes clients whose connection has gone `Inactive`. With our 3s QUIC
keepalive an orphaned connection keeps sending PINGs and **never** goes Inactive. So a stopped engine
leaves an immortal session still talking to the server; only **process death** reaps it. `stopXray` does
NOT clear it.

Two **disproved** rationales that used to be in the code (do not resurrect):
- ~~"tun.Handler has no Close, gVisor readers outlive stopXray"~~ — false for v26.7.11. `Handler.Close`
  exists (`proxy/tun/handler.go:216`), reaches `endpoint.Attach(nil)`, which signals the dispatcher's
  eventfd and joins the reader goroutines. Was true of 26.3.27.
- ~~"hysteria pool is behind a sync.Once, so a restart reuses the session"~~ — the `Once` only guards
  the map's creation; the key holds a POINTER to the `MemoryStreamConfig`, so a new `core.Instance`
  never reuses a client.

Before the split, killing the engine meant killing the process that also held the tun → Android tore
the VPN down → the status-bar VPN key **blinked**. Now only `:xray` dies; the app process keeps the tun
open across the restart, so the icon does not blink.

---

## 3. File map

**App process (`core/vpn/impl/src/androidMain/.../vpn/`)**
- `DeltaVpnService.kt` (~2100 lines) — the heart. Tun lifecycle, recovery ladder, probes, network
  callbacks, retry engine, split-tunnel apply.
- `PlatformVpnController.android.kt` — the main-process façade the UI calls: `connect` / `disconnect` /
  `revoke`. Writes `AndroidVpnRuntime.status` directly (same process now — no broadcast).
- `AndroidVpnRuntime.kt` — the `MutableStateFlow<ConnectionStatus>` the whole app observes.
- `SplitTunnelRoutingStore.kt` — the source of truth for per-app routing, read at establish time.
- `RecoveryTuningRepository` (`core:vpn:api`) — the "aggressive keepalive" preference; the service
  collects it into a field and each ladder reads the latest value. No cross-process hop: unlike the
  routing store, the service and the writer are both in the MAIN process (only `:xray` is separate).
- `BootRestoreReceiver.kt` — restores the tunnel after reboot / app update from persisted params.
- `ConnectionParamsStore.kt` — persisted last-good `ConnectionParams` for crash self-heal.

**`:xray` process + engine bridge (`core/xray/src/androidMain/.../xray/`)**
- `ipc/XrayEngineService.kt` — the bound service running IN `:xray`. Hosts libXray, hands the engine the
  tun fd, runs the protector.
- `ipc/RemoteXrayEngine.kt` — the app-side proxy. Every call is a Binder transaction into `:xray`.
  Process-wide singleton (`XrayEngineHolder`). Owns kill/respawn, death detection, the `opMutex`.
- `ipc/XrayIpc.kt`, `XrayIpcTransport.kt`, `XrayIpcPayloads.kt` — the wire (hand-rolled; AIDL is
  unavailable in the `com.android.kotlin.multiplatform.library` plugin — verified).
- `ipc/XrayHostBinder.kt` — the app-side callback `:xray` uses for the protect fallback.
- `protect/SocketProtector.kt`, `SocketProtectors.kt` — `LocalSocketProtector` (works, used),
  `RemoteSocketProtector` (Binder fallback, unused), `ChoosingSocketProtector` (latches on first socket).
- `PlatformXrayEngine.android.kt` — the actual libXray bridge (reflection `Invoke`), runs in `:xray`.
- `TunnelEngine.kt` — the narrow interface the VpnService uses (`startOnTun` / `stop` /
  `killEngineProcess` / `release`).
- `OtcLog.kt` — the single shared log file (`delta-vpn.log`, 100 MB cap, truncated in place). Both
  processes write it; the process tag is `main` / `xray`.
- `commonMain/.../XrayConfigSanitizer.kt` — builds the runtime JSON (tun inbound, log level, split
  tunnel, **quicParams**).

**Config sync (`feature/connection/logic-impl/.../connection/`)**
- `VpnSyncWorker.kt` — rebuilds the tunnel when the active config OR split-tunnel routing changes while
  connected (`restartWith`, §13). Takes the orchestrator's `Flow<ConfigSourcesState>` rather than the
  orchestrator, so the reapply path is unit-testable (`VpnSyncWorkerTest`) without Firestore.
- `data/VpnConsentRepositoryImpl.kt` — persisted VPN-disclosure acceptance (`vpn_settings` DataStore);
  interface `VpnConsentRepository` in `logic-api/.../domain`. Read by the consent gate (§4).

**Connect UI (`feature/connection/ui-impl/.../connection/`)**
- `VpnConsentScreen.kt` — the full-screen Play disclosure (`VpnConsentDialog`), gated in
  `ConnectionViewModel.handleConnectClick` before `VpnService.prepare()` (§4).

**Manifest**: `androidApp/src/main/AndroidManifest.xml` — the two `<service>` entries and the permission
set (no `SCHEDULE_EXACT_ALARM`, no AlarmManager).

---

## 4. Connection status model

`ConnectionStatus`: `Disconnected` · `PreparingPermission` · `Connecting` · `Connected` ·
`Disconnecting` · `Error(message)`.

- **`Connected` is published the moment the engine starts** (`runConnect`, "xray up"), NOT on the first
  confirmed probe. Rationale: `Connecting` makes the UI's `isBusy` true, which disabled the only button
  on the screen — a tunnel whose probe never confirmed left the user with no way out. A failing probe is
  the recovery ladder's problem, not a reason to lie about being busy.
- `publishStatus` dedupes repeats (the keepalive would otherwise re-assert `Connected` every 8s).
- The UI's `canConnect = selectedConfig != null` (NOT `&& !isBusy`) — the button is live in every state,
  and a tap in any transitional state means **stop** (`ConnectionViewModel.handleConnectClick`).

### The consent gate (Play requirement — do not remove or relocate)

On the **first** connect, `handleConnectClick` reads a persisted flag (`VpnConsentRepository`, backed by
the `vpn_settings` DataStore) and, if not granted, raises a **full-screen disclosure** (`state.showConsent`
→ `VpnConsentDialog` in `feature/connection/ui-impl/.../VpnConsentScreen.kt`) **before** anything calls
`VpnService.prepare()`. **Agree** persists the grant and falls through to `startConnection` (→ prepare →
`connect`); dismiss/back does nothing. Google Play's VpnService policy *requires* this disclosure in the
normal usage flow, ahead of the system VPN dialog — a settings entry or the OS dialog itself does not
satisfy it. The four statements (device-level tunnel · user-supplied server · local-only config · Firebase
diagnostics) are mandated content; don't trim them. See [`docs/play-release-checklist.md`](../docs/play-release-checklist.md) item 1.

---

## 5. The recovery ladder

Runs entirely in the app process, `NonCancellable` under `operationMutex`, with a wakelock held.
Entry: `recover(reason, rebuildTun)` → `runLadder(reason, rebuildTun, tuning)`.

```
guard: no usable underlying network  → stand down (a network appearing re-triggers)
guard: device slept > 20s mid-ladder → abandon (findings are stale; wake fires its own triggers)

T0  probe, patiently, up to patienceMs   "is it dead, or just not up yet?"
     - refreshUnderlyingFromSystem()
     - loop while awakeElapsed() < patienceMs:
         probe with growing timeout (600 → 1200 → 2500 → 4000 ms)
         first success → onRecoverySucceeded()
                       → if rebuildTun: rebuildTunForApps()  (see §7)
     - budgeted on AWAKE time (uptimeMillis), because the thing we wait for
       (hysteria2 QUIC idle timeout) is also a CLOCK_MONOTONIC timer

T2  replace the :xray process, backed off, uncapped   "the repair"
     - restartEngineForRecovery(): ++engineRestartAttempt, delay restartDelayFor(attempt),
       killEngineProcess() + startXrayOnTun(), then probe ENGINE_PROBE_DELAYS_MS
```

**There is no T1.** It used to be an in-process re-dial; deleted. Field record was 1 success in 4, and
after the process split it never ran (T0 always succeeds). It also could not have helped — the state a
restart must clear does not live in the engine instance (see §2).

`onRecoverySucceeded` == `noteTunnelHealthy`: clears the retry backoff (`engineRestartAttempt = 0`,
`retryPending = false`) and publishes `Connected`.

**Standing down.** Every wait point polls `standDownReason()`, which is `disconnecting` OR
`connectRequested`. The ladder is `NonCancellable` and can sit in T0's patience for the better part of a
minute, so anything deliberately asked for has to be able to cut in. `connectRequested` is the newer
half: it is armed in `onStartCommand` on every path into `runConnect` and disarmed once `runConnect`
holds `operationMutex`. It exists because the settings reapply is now a CONNECT rather than a disconnect
(§13), and the disconnect it replaced carried `disconnecting` with it. Best-effort by design — a lost
race costs latency, never the reconnect, which is queued on the mutex regardless.

### Why T0 owns most of the budget
Every probe failure ever recorded was a **timeout**, never a socket error. So "no answer" cannot tell a
broken path from one that has not finished coming up. The first version collapsed that into 1.9s and
produced a repair loop that fired on healthy tunnels and killed the process ~37×/day. Patience is not a
guess — it is sized to hysteria2's QUIC idle timeout (see §6).

### The two T0 profiles (`RecoveryTuning`)
T0 — and **only** T0 — is tunable by the user's "Aggressive keepalive" switch (Settings → Reliability,
**off by default**). `recover()` calls `currentRecoveryTuning()` once per run and passes the profile into
`runLadder`, so the setting applies live (no reconnect) and the `mode` reported to analytics is always the
profile the ladder actually ran under, even if the user flips the switch mid-recovery.

| | patience | probe timeouts | escalates into |
|---|---|---|---|
| **PATIENT** (default) | `T0_PATIENCE_MS` = 45s awake | 600 → 1200 → 2500 → 4000 ms | T2 |
| **AGGRESSIVE** (opt-in) | 1.9s awake ⇒ exactly 2 probes | flat 600 ms | T2 |

`PATIENT` is this file's own constants verbatim, so **with the switch off the ladder is byte-for-byte the
one documented above** — same patience, same growing timeouts.

`AGGRESSIVE` restores the pre-`:xray`-split T0 (two 600ms probes, 700ms apart, then condemn) and nothing
else. Both guards (no-network stand-down, 20s sleep-abandon), the INCONCLUSIVE probe handling and every
rung below T0 are **shared** — each fixes a measured field failure, not a preference, and reverting them
would reintroduce the overnight 670-futile-dial run and restarts fired on 75-minute-old evidence.
Crucially it escalates into **T2 (`:xray` restart)**, not the old `:vpn` kill — the tun is never closed
and the VPN icon never blinks. The trade it makes is the one §"Why T0 owns most of the budget" warns
about: a cold LTE bearer measured 23s from "cell appeared" to the first probe that could succeed, and
1.9s condemns it long before that.

---

## 6. quicParams — the reason recovery is fast

Set in `XrayConfigSanitizer` into every hysteria outbound's `finalmask.quicParams`:
`maxIdleTimeout=10s`, `keepAlivePeriod=3s` (`QUIC_MAX_IDLE_TIMEOUT_S`, `QUIC_KEEPALIVE_PERIOD_S`).

**Why.** After a path change, hysteria2 keeps using the QUIC session bound to the vanished interface
until quic-go's idle timeout expires; nothing re-dials before then. At xray's defaults (idle 30s, no
keepalive) that was 19–35s of dead tunnel. At 10s it is ~10s, and the keepalive is what makes 10s safe
(without it QUIC sends nothing on an idle connection and a healthy tunnel would time itself out).
Recovery is now typically 67–125ms because the probe finds the session already re-dialled.

The values are merged into whatever `quicParams` the share-link converter already wrote (bandwidth,
port-hopping) — do NOT overwrite the block. Verified against xray-core's real parser.

Cost: radio wakeups every 3s while the CPU is awake. Nothing during deep sleep (the timer does not
advance while suspended). This is the tradeoff tracked in
[`docs/android-doze-battery-plan.md`](../docs/android-doze-battery-plan.md).

---

## 7. Tun rebuild — the "apps see a new network" mechanism

On a **confirmed underlying-network MOVE** (`TunnelStart.PATH_CHANGED`), after T0 succeeds, the tun is
**rebuilt** (`rebuildTunForApps`): a fresh `establish()`, engine restarted on the new fd, old fd closed.

**Why it exists.** A path change keeps the SAME VPN network object, so apps behind the VPN see only a
capabilities change, never an `onAvailable`. Some apps (Telegram) wait for a network *signal*, not for
the tunnel — measured: Telegram sat in "waiting for network" for 12 minutes over a provably healthy
tunnel, and recovered instantly on the next switch. A fresh connect over the same network was always
fine, because then the VPN network is new. The rebuild gives apps that new-network signal
(`onLost`/`onAvailable` with a new netId — confirmed in the field: `tun1 → tun2`).

**Unconditional on a MOVE — do not re-gate on "the tunnel was down".** That was tried and is wrong:
apps need the signal because THEIR network changed transport, which is independent of tunnel health. And
gating left `engineNetwork` pointing at the old network, so a switch BACK to it read as "no move" and no
signal fired — Telegram stayed dead. See git history around `tun rebuilt`.

**Measured cost** (accepted, paid during a switch that already broke every tunnelled connection):
- ~600–850ms with the engine restarting.
- ~90ms in which the **physical interface is the default** for our own UID (a real leak window — netd
  reinstalls per-UID rules asynchronously; this is also why `PROBE_ESTABLISH_GRACE_MS` exists).

`rebuildTunForApps` returns `Boolean`; on failure the ladder escalates to `restartEngineForRecovery`
rather than reporting Connected over a tun nothing reads.

---

## 8. Triggers (what starts a `startTunnelJob`)

`startTunnelJob(reason, start, externalTrigger = true)`. `TunnelStart`:
`KEEPALIVE_ONLY` · `PROBE_FIRST` · `FORCE_RECOVER` · `PATH_CHANGED` (ordered for coalescing).

| Source | Trigger | `externalTrigger` |
|---|---|---|
| connect completes (`startMonitoring`) | `PROBE_FIRST` "connected" | **false** |
| `onCapabilitiesChanged(VALIDATED)`, network **moved** | `PATH_CHANGED` | true |
| `onCapabilitiesChanged(VALIDATED)`, same net **confirmed** | `PROBE_FIRST` | **false** |
| `onLinkPropertiesChanged` (signature changed) | `PROBE_FIRST` | true |
| screen on | `PROBE_FIRST` | true |
| Doze idle-exit (`ACTION_DEVICE_IDLE_MODE_CHANGED`) | `PROBE_FIRST` | true |
| engine process died unexpectedly (`onEngineDied`) | `FORCE_RECOVER` | true |
| keepalive: 2 consecutive probe fails | `recover("keepalive")` directly | — |

"Moved" vs "confirmed" is decided by `engineNetwork` (the network the running engine dialled over), NOT
by `validatedUnderlying` (which `onLost` clears) and NOT by `underlyingSeeded` (sticky). See §11.

If a recovery already owns the tunnel, a non-keepalive trigger is **deferred** (one slot, strongest
wins) and drained before the ladder falls into the keepalive; keepalive triggers are dropped.

---

## 9. The health probe

`probeTunnel(timeoutMs): Boolean?` — a DNS A-query to `1.1.1.1:53` that must round-trip through the tun.

Three load-bearing checks: (1) `connect()` forces the kernel to pick the source address now, and we
assert `localAddress == TUN_ADDRESS` (`10.77.0.2`) — the only way to know the probe went through the tun
and not onto the physical network; (2) the reply's DNS txid + QR bit are verified; (3) a probe inside
`PROBE_ESTABLISH_GRACE_MS` (500ms) of `establish()` is delayed, not answered — netd installs per-UID
rules asynchronously and a probe in that window egresses physically.

**Tri-state.** `true` = confirmed round trip; `false` = confirmed dead; **`null`** = learned nothing
(the process was frozen mid-probe — detected when elapsed ≫ timeout × `FROZEN_PROBE_FACTOR`). `null` is
NEVER collapsed into either verdict: dead would manufacture failures out of Doze, alive would reset the
backoff and report Connected on a tunnel we never reached. Retried `PROBE_FROZEN_RETRIES` times.

It is a **UDP** probe. A separate TCP diagnostic (`TCP_PROBE_EVERY_N_ROUNDS`) exists TEMP for diagnosis
— it does NOT vote on health. The probe socket is deliberately NOT protected (its traffic must traverse
the tun — that is what we test).

Keepalive cadence: `KEEPALIVE_INTERVAL_MS` 8s screen-on, `KEEPALIVE_INTERVAL_SCREEN_OFF_MS` 60s off;
`KEEPALIVE_FAILS_BEFORE_RECOVER` = 2 (one blip tolerated).

---

## 10. Doze & the retry engine

**No AlarmManager, no `SCHEDULE_EXACT_ALARM`.** Measured: an "exact" alarm fired 14–15 MINUTES late in
Doze, 3× of 3. The app process now survives (nothing to resurrect), so recovery only needs to act when
the CPU is awake. Wake triggers: idle-mode receiver, screen-on, network callbacks, keepalive.

Sleep detection uses `elapsedRealtime` (counts through suspend) vs `uptimeMillis` (stops during
suspend); their difference is measured sleep. The ladder abandons if it slept >
`LADDER_ABANDON_SLEEP_MS` (20s), because everything it learned is stale.

**Retry engine** (distinct from the ladder): `scheduleRetry` fires only when xray FAILS to start (bad
config, `establish()` null, etc.). It sets `retryPending`, backs off `RETRY_BACKOFF_MS`
(1s→2s→5s→15s→60s→300s), and `retryNow` short-circuits the wait on an external trigger. **`retryPending`
is cleared the moment the engine starts** (`runConnect`, "xray up") — NOT only on a confirmed probe.
This is critical: see §13 (the two-day storm).

---

## 11. Underlying-network state machine

Three fields, three jobs — do NOT merge them:

- **`lastUnderlying`** — what we currently advertise via `setUnderlyingNetworks`. Written by
  `adoptUnderlying`, `onLost`, `refreshUnderlyingFromSystem`.
- **`validatedUnderlying`** — the network `onCapabilitiesChanged(VALIDATED)` last acted on. Cleared by
  `onLost`. Dedupes repeat VALIDATED callbacks.
- **`engineNetwork`** — the network the running engine dialled over. Set in `startXrayOnTun`, cleared in
  `stopXray`. **This is what decides "moved vs confirmed"** on a validated callback.

`scanBestUnderlying`: ranks non-VPN INTERNET networks (validated > not-suspended > non-cellular > higher
netId — newest-first, because the network that just appeared is the one being handed to).
`refreshUnderlyingFromSystem`: repairs `lastUnderlying` only if the cached network vanished — a live
callback always wins (callbacks are dropped across Doze, so a recovery cannot blindly trust cache, but
it must not overrule a fresh correct callback either).

`onLost` advertises the best REMAINING network (`excluding` the lost one — ConnectivityManager still
enumerates a network briefly after announcing its loss) rather than blanking, so the status bar does not
go blank mid-handover.

`setMetered(false)` on the builder — WITHOUT it the VPN network is metered on every underlying network
including unmetered Wi-Fi (targetSdk Q+ default), and apps throttle background work / Data Saver blocks
it. This was a suspected cause of the Telegram symptom.

IPv6: the tun claims `::/0` with NO v6 address, so a v6 connect fails instantly (ENETUNREACH) and Happy
Eyeballs falls through to IPv4, which the tunnel carries. Giving the tun a v6 address would make apps
PREFER a path we cannot forward.

---

## 12. The IPC surface & the engine singleton

`RemoteXrayEngine` is a **process-wide singleton** (`XrayEngineHolder`). Two callers share it: the
config screens (validate only) and the VpnService (the tunnel). One binding, not two — a second
`BIND_AUTO_CREATE` binding would resurrect `:xray` the moment the first killed it.

**`opMutex` serialises WHOLE engine operations** (`validate` / `startOnTun` / `stop` /
`killEngineProcess`) against each other. `connectionMutex` only ever covered the bind inside `binder()`,
so a kill could land BETWEEN a start's bind and its START transaction. `opMutex` is always the OUTER
lock; `connectionMutex` the inner one; ordering never inverts → no deadlock.

Kill/respawn: `killEngineProcess` sends `TX_SUICIDE` (ONEWAY — the receiver dies without replying),
waits `DEATH_TIMEOUT_MS` (2s) for `linkToDeath`, then `forceKill` by pid (guarded on `isBinderAlive`, so
the pid cannot have been recycled), then `unbind` AFTER the process is gone (so `BIND_AUTO_CREATE` cannot
race a respawn). Unexpected death (crash/LMK) fires `onUnexpectedDeath` → `onEngineDied` → recovery.

The engine start is **idempotent** (`PlatformXrayEngine.start`): if `isRunning()`, `stopXray` first,
then `runXrayFromJson`. libXray refuses with "xray is already running" while a `core.Instance` is live;
a stale one from a reused process generation would otherwise fail the start and (before A/§13) drive a
storm.

Tun fd crosses as a `ParcelFileDescriptor` in the SAME transaction as the config (never set-then-start
— a failed start between them would strand a descriptor in a process nobody restarts). The app-side
`startXrayOnTun` dups `tunInterface` at the last moment; `:xray` `detachFd`s it and closes the previous
one. Descriptor reuse verified (199→163→164→163→166 across 4 rebuilds — no leak).

**protect()** works LOCALLY from `:xray` via an anonymous `object : VpnService() {}` — verified on
device ("cross-process protect works locally"). `protect(int)` is a static `NetworkUtilsInternal`
delegation with no per-instance state; netd keys on the calling UID, shared by both processes. The
Binder fallback (`RemoteSocketProtector`) is implemented but unused. `ChoosingSocketProtector` latches
the verdict on the first socket. An unprotected socket loops back into the tun — failures are logged as
transitions, not swallowed.

---

## 13. Config re-apply & split tunnel (`VpnSyncWorker.restartWith`)

Changing config OR **split-tunnel routing** while connected changes `VpnSyncWorker`'s `ConfigKey` →
`restartWith`. **This is the split-tunnel apply path** — editing exclusions rebuilds the tunnel to apply
new routing (per-app routing is baked into `establish()`; it cannot be changed in place).

### One CONNECT, no disconnect — and why that is not a style choice

`restartWith` sends a **single `connect()` into the live, still-foreground service.** There is no
`disconnect()`, and no waiting for `Disconnected`.

It used to be disconnect → await `Disconnected` → connect, and **that shape is what crashed the app**
(§14). `runUserDisconnect` publishes `Disconnected` — the thing that releases the worker — on the
statement immediately before `stopUnlessRestarted`. The worker resumes on a *different* dispatcher, so
its resumption is queued rather than run inline, and it then has to wake a thread, log, build an intent
and issue its own heavier AMS transaction. The teardown's `stopServiceToken` gets there first, every
time. The service was destroyed, and the connect that landed a moment later had to rebuild it from
`onCreate` while the FGS deadline armed by that very connect was already running.

A plain CONNECT into a service that is already foreground has no such window: `startAsForeground()`
satisfies the deadline in the first statement of `onStartCommand`, and nothing stops the service at all.

**Do not "tidy" this back into a disconnect/reconnect pair.** Pinned by
`VpnSyncWorkerTest` — three of its five cases fail the moment a `disconnect()` reappears.

`restartWith` **validates BEFORE the reconnect.** Validation runs in `:xray`, which the reconnect kills;
validating after that raced the just-killed process into a `DeadObjectException` → "engine unavailable"
→ the tunnel stayed down until a manual reconnect. Doing it first also means a genuinely bad new config
never costs the working tunnel.

`activeKey` is set **before** `connect()`, not after: `connect` publishes `Connecting` synchronously into
the very flow `restartWith` runs inside, so the `Connected` that follows must find the new key already
recorded — otherwise the echo reads as another unapplied change and reapplies forever.

### What `runConnect` has to do because the disconnect no longer does it

`runConnect` is now re-entered over a LIVE session, which no other path does. Guarded by
`tunInterface != null`, it:
- **`reportSessionEnd(SessionEndReason.RECONNECT)`** — the session genuinely ends (new tun, new engine),
  and it must be measured before the success block resets `connectedAt` and the keepalive counters.

**It deliberately does NOT cancel `tunnelJob`,** and this is a trap worth spelling out because the
opposite looks obviously right (the old keepalive is about to probe a tun this function closes).
Cancelling it loses the guarantee that the new session gets a watcher: `startMonitoring()` at the end of
`runConnect` can be deferred by the `recovering` guard — an old keepalive's `recover()` blocked on the
`operationMutex` we are holding reads as `recovering` — and the only thing that ever drains that
deferral is the tail of that same job. Left alone, both branches end with a watcher (either
`startTunnelJob` installs one, or the old job's `drainPendingTrigger` does and falls into the
keepalive). Cancelled, one branch ends with a live tunnel nobody is watching. The cost of leaving it is
bounded: everything destructive the old loop can reach needs `operationMutex`, which `runConnect` holds
until the new tunnel is up — at worst it publishes one stale `Connected` or spends one futile ladder
run.

Deliberately NOT carried over from `runUserDisconnect`: `paramsStore.clear()` (the new params were just
persisted by `onStartCommand`), `publishStatus(Disconnecting)` (the UI goes Connected → Connecting →
Connected, which is the truth), and the retry cancellation (`runConnect` clears `retryPending`/`retryJob`
itself on "xray up").

---

## 13b. The foreground-service contract (`onStartCommand`)

`startAsForeground()` is the **first statement** of `onStartCommand`, and it runs for **every** start —
CONNECT, DISCONNECT, REVOKE and the null-intent sticky restart alike.

The deadline belongs to the START, not to what the intent means. From the instant anyone calls
`startForegroundService()`, Android allows a few seconds to reach `startForeground()`; miss it and the
**whole process** dies with `ForegroundServiceDidNotStartInTimeException`. It is not catchable — the
system throws it into the main looper.

It used to skip DISCONNECT/REVOKE. That was safe only by accident: `PlatformVpnController.sendStop`
happens to use `startService()`, which starts no deadline. One call site switching to
`startForegroundService()` would have made the crash unconditional.

### Stopping: always `stopSelf(startId)`, never `stopSelf()`

Every teardown ends in **`stopUnlessRestarted(startId)`** — there are four (sticky stand-down,
permission-missing, `runUserDisconnect`, `fail`) and none of them calls `stopSelf()` bare.

`stopSelf(startId)` is a no-op when a NEWER start has arrived. That is correct and worth having for any
start landing during the slow part of a teardown (a Connect tap while `stopTunnel()` kills `:xray`).

**It was never the fix for the `restartWith` reapply race, and must not be documented as one.** On that
path the stop always won: `runUserDisconnect` published `Disconnected` — which released the worker — one
statement before the stop, and a cross-dispatcher wakeup plus an intent build plus an AMS transaction
cannot beat the next statement. That race is now gone because the reapply no longer disconnects at all
(§13); this helper covers the remaining case, a start that lands during the SLOW part of a teardown.

Two invariants inside that helper:
- **[startId] is the id of the start that STARTED this teardown**, captured synchronously in
  `onStartCommand` and carried down. Reading `lastStartId` at stop time inverts the test — it would be
  the *newer* start's id, and the service would stop exactly when it must not. `lastStartId` exists only
  for the paths with no start of their own (`onRevoke`, a retry).
- **`stopForeground` lives INSIDE the helper**, after the check. When the stop is skipped the
  notification stays up on purpose: the newer start has already promoted us and is about to build a tun,
  so clearing foreground would leave a VPN running as a background service.

Two things that look like improvements and are not:
- **Do not pair it with an immediate `stopForeground` in the teardown branches.** The tun is still up
  when `onStartCommand` returns; dropping foreground before the teardown has run invites the process to
  be frozen mid-disconnect. `runUserDisconnect` already ends in `stopUnlessRestarted`, which stops first
  and drops foreground only if the stop actually took, and it is the only correct place — note it sits inside `operationMutex`, so a recovery ladder can legitimately
  delay it (see §5).
- **Do not let it throw.** It is guarded and reports a non-fatal instead: it now also runs on the
  teardown starts, and `startForeground` can be refused by the background-start rules. When the system
  refuses it stops us anyway, so a logged `VPN_TUNNEL` non-fatal beats an opaque system stack.

---

## 14. Historical bugs — do not reintroduce (each cost real field time)

- **`ForegroundServiceDidNotStartInTimeException` at connect.** Crashlytics, Android 16 / OxygenOS.
  Reproduced by applying split-tunnel settings to a running tunnel. The thread dump had a coroutine
  parked in `VpnService.Builder.establish()` → binder → `IVpnManager.establishVpn`, holding
  `operationMutex`, while the deadline for a *second* start ran out — our own in-flight `establish()`
  stalls system_server, and `startForeground()` is a binder call into the same place. **Root cause of
  the second start: `restartWith` disconnected and reconnected**, which destroyed the service and made
  the reconnect pay for `onCreate` under a deadline that was already running. Fixed by removing the
  teardown from that path entirely (§13), on top of the unconditional promotion (§13b).
  **A `stopSelf(startId)` guard was tried first and did not work** — see §13b for why it cannot.
  Still open: `establish()` is unbounded, and `PlatformVpnController.connect()` fires
  `startForegroundService` with no check for a connect already in flight.
- **Two-day reconnect storm.** `retryPending` was cleared only by a confirmed probe, but
  `startTunnelJob` short-circuited to `retryNow` while it was set — so the "connected" self-trigger after
  every reconnect re-fired the connect, and the probe that would clear the flag never ran. Fixed by
  clearing `retryPending` on "xray up" (§10) + `externalTrigger=false` on self-triggers (§8). Trigger
  was "xray is already running" (§12).
- **Split-tunnel change left the tunnel down** — validate-after-kill race (§13).
- **Split-tunnel change killed the app process** — the disconnect/reconnect reapply, above.
- **Overnight hour-long ladder with no network** — 670 futile dials. Fixed: stand down when no usable
  underlying network; abandon if slept mid-ladder (§10).
- **Telegram "waiting for network" 12 min** — apps wait for a signal, not the tunnel (§7).
- **Frozen probe read as dead** → restart loop. Fixed by the tri-state (§9).
- **Connecting disabled the only button** → dead-end. Fixed (§4).

**Disproved diagnoses — do not chase again:** protect fails from `:xray` (it works); stale tun-side
connections need an engine reset to RST them (killing the engine mid-recovery destroyed fresh
reconnections); apps see NOT_VALIDATED / no transports (measured validated=true); TCP through the tunnel
is broken (22/22 probes pass); "it's a regression from the split" (the same Telegram silences appear
dozens of times in the pre-split log).

---

## 15. Diagnostic markers in the log (`delta-vpn.log`)

Grep-able signals. Process tag `main` vs `xray`; xray-core lines start with `20YY/…` and are in **UTC**
(= local − 5h in the field logs seen so far).

- `recover(<reason>): T0 OK — alive after <ms>ms (attempt N)` — recovery latency + how many probes.
- `underlying MOVED (validated)` / `underlying confirmed (validated)` — the move-vs-confirm decision.
- `tun rebuilt(path changed)` + `apps-eye: onLost / onAvailable` — the app-signal mechanism firing.
- `apps-eye: caps … notMetered=…` — TEMP diagnostic: the network AS APPS SEE IT (default-network
  callback = our own VPN network).
- `tcp-probe OK/FAILED` — TEMP diagnostic: TCP through the tunnel (does not vote on health).
- `cross-process protect works locally` — the protect verdict.
- `retry: … — attempting now instead of waiting` — retryNow fired. A tight run of these = a storm (bug).
- `xray is already running` — the non-idempotent-start bug (should be impossible now).

The `apps-eye` and `tcp-probe` lines are marked `TEMP (diagnosis)` and come out once the tun-rebuild
approach is settled.

---

## 16. Analytics instrumentation (main process only)

The service fires 8 Firebase events through `AnalyticsManager` (`core:analytics`, an **androidMain-only**
dep — the module has no macOS target). `DeltaVpnService` is a `KoinComponent` and `by inject()`s the
manager. Fire-and-forget; carries **outcomes/enums/coarse buckets only** — never a server, credential,
config, destination, or raw count/timestamp. NEVER instrument from `:xray` (no Firebase there).

Where each fires — do not double-fire or move these:
- `vpn_connected` — `runConnect` success (`via` = `pendingConnectVia`, set in `onStartCommand`: fresh vs
  restore) AND `onRecoverySucceeded` (`via=recovery`; that hook is the recovery-only path, so it does
  not fire on routine keepalives).
- `vpn_tunnel_confirmed` — `noteTunnelHealthy`, gated by `tunnelConfirmedThisSession` (an AtomicBoolean
  reset per session in `runConnect`) so it fires once, not on every keepalive.
- `vpn_error` — `fail(message, category)` and the `NotPreparedException` path (category from the call
  site, never `Error.message`).
- `vpn_session_end` + `vpn_keepalive_health` — `reportSessionEnd(reason)` from `runUserDisconnect(reason)`,
  `fail()`, and `runConnect`'s live-session close-out (`RECONNECT`, §13 — so a reapply is no longer
  miscounted as a user disconnect). Note this does NOT make the two events balance:
  `onRecoverySucceeded` fires `vpn_connected(RECOVERY)` inside a live session with no end of its own, so
  `vpn_connected` legitimately outnumbers `vpn_session_end` — and a reapply still reports `via = FRESH`,
  which it did before this change too. Do not read either count as "sessions started";
  no-ops unless the session reached Connected (`connectedAt != null`). Dead/inconclusive
  counts come from `keepAliveLoop` (INCONCLUSIVE = Doze-frozen, counted apart so a freeze ≠ a failure).
- `vpn_recovery` — once per `runLadder` run, fired in `recover()`. `runLadder` and
  `restartEngineForRecovery` now **return `RecoveryOutcome?`** (null = any `standDownReason()` — a user
  disconnect OR a connect waiting on the mutex — not reported) purely so `recover()` can report the outcome — no control-flow changed. Trigger is mapped
  from the reason string (`recoveryTriggerOf`); `transport` is the coarse underlying type only; `mode`
  is the `RecoveryMode` the run actually used (resolved once in `recover()` and passed into `runLadder`,
  so a mid-run toggle cannot make the event disagree with the ladder).
- `settings_aggressive_keepalive_toggled` — the switch changed (boolean only). Pairs with `mode` above:
  one says how many opt in, the other whether the impatient ladder actually recovers more often.
- `vpn_engine_death` — `onEngineDied`, best-effort `ApplicationExitInfo.reason` for `:xray` → enum.
- `vpn_tun_rebuild` — `rebuildTunForApps` branches (rebuilt-ok / establish-failed / engine-not-back).

Keep `RecoveryTrigger`/`RecoveryOutcome`/`EngineDeathReason`/`ConnectVia` (in `core:analytics`) in step
with the states here. Full plan + deny-list: [`docs/analytics-events.md`](../docs/analytics-events.md).

### Error reporting (caught non-fatals → Crashlytics)

Separately from analytics, unexpected **caught** errors are reported via `ErrorReporter.report(domain,
throwable)` (`core:error-reporting`, a `commonMain` dep — the module targets android/ios/jvm/macos and
OWNS the `CrashReporter` port; the concrete binding is real Crashlytics on Android/iOS/macOS, stdout on
JVM). The service injects it
(`by inject()`) and reports at: the `scope` CoroutineExceptionHandler, `runConnect` failure, tun-rebuild
establish failure, tun-fd dup failure, the `startTunnelJob` recovery-dispatch catch, idle-receiver and
network-callback registration failures, and `protectSocket` throws (`SOCKET_PROTECT`). `ConnectionParamsStore`
/`SplitTunnelRoutingStore` take a nullable reporter (the service passes it; other callers pass null) and
report file save/load failures (`DATASTORE`). `RemoteXrayEngine` (the MAIN-process IPC proxy) reports
binder/transaction/decode/bind failures (`XRAY_IPC`) — but the `:xray` side (`XrayEngineService`,
`PlatformXrayEngine.android`) NEVER reports (no Firebase there). The impl scrubs the throwable message
(keeps type + stack); expected control-flow (probe timeouts, close/flush) is deliberately not reported.
The **iOS NE tunnel** (`DeltaTunnelCore`, the iOS analog of `:xray`) is a separate process with no
Koin: it reports via `core:firebase-crashlytics`, a **Crashlytics-only** Firebase surface that links only
the Crashlytics closure — never Firestore/gRPC — so the appex stays slim. It reuses the same
`ErrorReporter` scrubbing. Full map: [`docs/error-reporting.md`](../docs/error-reporting.md).

---

## 17. Build & verify

```bash
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease test
```

`:core:xray:assemble` fails on an unrelated pre-existing jvm-target issue — build the app, not that
task. After editing a `.kt`, do NOT re-read to verify (Edit would have errored); a source→class
timestamp check confirms recompilation if needed. The bare `test` task does NOT run the KMP feature
modules' `jvmTest` — run those explicitly when touching them.

Anything about process priority, the leak window, in-Doze callback delivery, or battery cost is only
answerable **on a device** — see the "Нерешённое" section of
[`docs/vpn-architecture-audit.md`](../docs/vpn-architecture-audit.md) for the exact measurements.
