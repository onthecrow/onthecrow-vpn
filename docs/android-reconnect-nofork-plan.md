# No-fork recovery plan

**Status:** implementable specification. Supersedes Step 2 (the xray-core fork) of `docs/android-reconnect-analysis.md`. Everything else in that document that is independent of the fork decision is folded in here.

**Adjudicator's note on the working tree.** Several items the analysis lists as "to do" have already landed. Verified by reading the tree at the time of writing:

| Analysis claim | Actual state | Evidence |
|---|---|---|
| "`WAKE_LOCK` verified absent today" | **Present** | `androidApp/src/main/AndroidManifest.xml:13` |
| Step 1 honest probe not written | **Written** | `DeltaVpnService.kt:517-547` — `connect()`s, asserts `localAddress == TUN_ADDRESS`, checks txid + QR bit |
| `registerProtectControllers` not idempotent (D17, "blocking prerequisite") | **Fixed** | `PlatformXrayEngine.android.kt:180` — `if (!protectControllersRegistered.compareAndSet(false, true)) return` |
| `ACTION_DEVICE_IDLE_MODE_CHANGED` receiver "new" | **Registered** | `DeltaVpnService.kt:633` |
| `refreshUnderlyingFromSystem()` "deleted from tree" | **Restored** | `DeltaVpnService.kt:209, 628, 649` |
| Capability / link-property triggers "new" | **Present** | `DeltaVpnService.kt:746` (`onCapabilitiesChanged`), `:756` (`onLinkPropertiesChanged`) |
| `FOREGROUND_SERVICE_SPECIAL_USE` (D18 open) | **Declared** | `AndroidManifest.xml:7`, subtype `vpn` at `:60-62` |

The systems expert's two corrections to the analysis document are both confirmed. Do not re-do this work. What remains is genuinely: the pool-eviction replacement, `runRedial()`, the disconnect/recovery split, and the restart tier.

---

## Verdict on in-process pool eviction (fd shutdown)

### DROP IT. Do not spend a build on it. Not even behind a flag.

The three experts split 2–1. The dissent rests on a single inference that is **refuted by source**, so this is not a judgement call.

### The disagreement, and how it is settled

The Android expert argued the socket is `connect()`ed, and built the entire viability case on it:

> "`dialer.go:172` calls `internet.DialSystem(...)`; the switch at `:180-186` does `remote = conn.RemoteAddr().(*net.UDPAddr)`. `RemoteAddr()` returns `nil` on an unconnected `*net.UDPConn` and that assertion would panic. So it is a connected UDP socket … **This is the precondition the whole idea rests on, and it holds.**"

The inference is that `RemoteAddr()` is `*net.UDPConn.RemoteAddr()`. It is not. **VERIFIED** — the value flowing into that type switch is a `*internet.PacketConnWrapper`, and that type defines its own `RemoteAddr()`:

```go
// transport/internet/system_dialer.go:151-167
type PacketConnWrapper struct {
	net.PacketConn
	Dest net.Addr
}
func (c *PacketConnWrapper) RemoteAddr() net.Addr {
	return c.Dest        // ← a userspace struct field. Never touches the kernel.
}
```

`Dest` is set at construction from `net.ResolveUDPAddr("udp", dest.NetAddr())` (`system_dialer.go:62-65, 84-87`) — pure userspace address parsing. The assertion at `hysteria/dialer.go:181` therefore **cannot panic and proves nothing about socket state.** The precondition the whole idea rests on does not hold.

And the socket's actual creation is unambiguous (**VERIFIED**, `transport/internet/system_dialer.go:53-87`):

```go
if dest.Network == net.Network_UDP && !hasBindAddr(sockopt) {
    srcAddr := resolveSrcAddr(net.Network_UDP, src)
    if srcAddr == nil { srcAddr = &net.UDPAddr{IP: []byte{0,0,0,0}, Port: 0} }
    var lc net.ListenConfig
    lc.Control = func(...) { /* our protectFd controllers run HERE, before bind */ }
    packetConn, err := lc.ListenPacket(ctx, srcAddr.Network(), srcAddr.String())   // "udp", "0.0.0.0:0"
    ...
    return &PacketConnWrapper{PacketConn: packetConn, Dest: destAddr}, nil
}
```

`hasBindAddr` (`:46-48`) is false unless both `sockopt.BindAddress` and `sockopt.BindPort` are set, which this app's config does not do. So: **`ListenPacket` on `0.0.0.0:0`, never `connect()`ed.** Every write is a `sendto()` with an explicit destination (`PacketConnWrapper.Write`, `:161-163` → `WriteTo(p, c.Dest)`).

**The Go expert and the systems expert independently reached this conclusion from the same source. The Android expert's contrary claim is a misattributed method call. 2–1 becomes 3–0 once the error is pointed out.**

### Why it fails even granting the best case

The two dissenting experts disagree on the *read*-path detail — the Go expert predicts `recvmsg` returns `(0, nil)` producing a 100 %-CPU busy-spin in `Transport.listen`; the systems expert predicts `EAGAIN` and a goroutine that parks forever. **That disagreement does not need settling, because both outcomes are "no error ever reaches quic-go," and both are unacceptable.** One burns a core, the other hangs silently. Neither tears the connection down.

The only path that *would* work is the write path — `send_queue.go:91-99` treats any write error other than `EMSGSIZE` as fatal, returning it from `Run()`, which triggers `destroyImpl` (`connection.go:595-599`) → `ctxCancel` (`:572`) → `c.conn.Context()` Done → `status()` returns `StatusInactive` (`hysteria/dialer.go:129-139`) → next `dial()` rebuilds (`:149-156`). That chain is real and both experts verified it.

But **`udp_sendmsg()` has no `sk_shutdown` check** — that check is TCP-only, in `tcp_sendmsg_locked`. `shutdown()` on a UDP socket does not make sends fail. (INFERRED from kernel behaviour; both dissenting experts state it independently. It does not need on-device confirmation because the idea is already dead on the connectedness finding.)

So: the one lever that works is the one `shutdown()` cannot pull, on a socket whose assumed precondition is false.

### The neighbouring ideas, all rejected

| Idea | Verdict |
|---|---|
| `close(fd)` | fd number is Go's; freeing it lets Go hand it to an unrelated socket. Subsequent QUIC writes enter a stranger's socket and `pktConn.Close()` (`hysteria/dialer.go:143`) closes a stranger's fd. **Reject.** |
| Record the raw int, act later | Use-after-free on an fd number. `syscall.RawConn.Control`'s contract is explicit: "The file descriptor fd is guaranteed to remain valid while f executes but not after f returns" (`$GOROOT/src/syscall/net.go:9-13`, enforced by `incref`/`decref` in `internal/poll/fd_posix.go:56-63`). **Reject.** |
| `Os.dup()` inside the callback, then `shutdown` the dup | Safe (shared `struct sock`, refcounted) but **ineffective** — same §above. The dup technique is correct and worth keeping as a *diagnostic* instrument only. |
| `dup2(nonSocketFd, goFd)` → `ENOTSOCK` → fatal write error | Mechanically works. **Irreducible TOCTOU**: no atomic compare-and-`dup2` exists; if Go closes microseconds earlier the number is recycled and you clobber the tun, a Binder fd, or an ART fd. Failure is silent and indistinguishable from the bug being chased. **Reject unconditionally** under a reliability-first mandate. |
| `connect()` our dup to a blackhole | Linux `udp_sendmsg` accepts `sendto()` with an explicit address on a connected socket. Sends keep working. Degrades to the idle timer. **Reject.** |
| `SO_SNDBUF = 0` → `ENOBUFS` | `ENOBUFS` *is* fatal to `sendQueue.Run`, but `SO_SNDBUF` has a kernel floor and UDP wmem is released on transmit. Non-deterministic. **Reject.** |
| Forge a `CONNECTION_CLOSE` / stateless reset via the tun we own | Requires 1-RTT AEAD keys or the peer's 16-byte reset token. Off-path termination is precisely QUIC's threat model. **Reject.** |
| `SO_BINDTODEVICE` to `lo` | Needs `CAP_NET_RAW`. App UIDs do not have it. **Reject.** |

### The one thing to salvage regardless

Ship the **`protectFd` counter** as a permanent instrument. `protectFd` fires only when a new socket is created (`system_dialer.go:66-79`, inside `lc.Control`, before bind), so:

> **A recovery that produces no `protectFd` call did not get a fresh upstream connection.**

That is a zero-cost, unambiguous oracle for whether *any* eviction technique worked — strictly better than inferring it from the probe. It should have existed before any of this was attempted, and it is the acceptance check for half the items below. (Change 1, below.)

---

## Final ladder

### The governing constraint, stated plainly

With no fork, **the floor on recovery latency is quic-go's `MaxIdleTimeout`, counted in awake CPU time.** Nothing app-side beats it in-process. The pool self-heals correctly (`hysteria/dialer.go:149-156` — **VERIFIED**, and the map stores a mutable `*client` pointer that is overwritten in place, so nothing leaks), but only once `c.conn.Context()` is cancelled, and the only app-reachable way to cause that is to let the idle timer fire.

So the no-fork design attacks the *constant*, not the mechanism: **drive `MaxIdleTimeout` from 30 s down to 8 s via config, and make it an accurate liveness signal by enabling keepalive.**

### Lever A — `maxIdleTimeout` + `keepAlivePeriod` (VERIFIED, config-only)

The 30 s is a default, not a constant (**VERIFIED**, `transport/internet/hysteria/dialer.go:245-250`):

```go
if quicParams.MaxIdleTimeout == 0 { quicConfig.MaxIdleTimeout = 30 * time.Second }
// if quicParams.KeepAlivePeriod == 0 {
// 	quicConfig.KeepAlivePeriod = 10 * time.Second
// }                                    ← commented out; keepalive is DISABLED today
```

Both are reachable from JSON. **VERIFIED end to end:** JSON tags `maxIdleTimeout` / `keepAlivePeriod` at `infra/conf/transport_internet.go:640-641`; bounds enforced at `:1951-1955` (`maxIdleTimeout ∈ [4,120]`, `keepAlivePeriod ∈ [2,60]`); built into `internet.QuicParams` at `:1979-1980`; consumed at `dialer.go:226-227`.

```jsonc
"streamSettings": {
  "finalmask": { "quicParams": { "maxIdleTimeout": 8, "keepAlivePeriod": 3 } }
}
```

**Keepalive is not optional, and the reason matters.** Without it, an 8 s idle timeout tears down *healthy but idle* connections. With a 3 s keepalive, PINGs elicit ACKs that reset the idle timer, so an idle timeout means the path is **genuinely dead**. Keepalive is what converts `maxIdleTimeout` from a liability into an accurate liveness detector. Ship them together or not at all.

**Re-adjudication of D2 — the analysis was wrong on this, and it is what unblocks the no-fork design.** The analysis demoted `quicParams` to defence-in-depth partly because "`dialer.go:448-461` captures `quicParams` **only on pool insert**, so injecting it is a no-op for the corpse you are currently stuck behind." That objection is true in the abstract and **irrelevant in deployment**: the pool is a *process-global* that starts empty, and the config is supplied at the first `RunXrayFromJSON` in that process. Every pool entry is therefore **born** with `maxIdleTimeout: 8`. There is no "corpse with old params" unless you change the config in-process — which this design forbids (see Change 8). D2's objection (ii) is retired. Objections (i) (frozen timers) and (iii) (unverified emission) stand and are handled below.

**Adversarial — exactly when Lever A fails:**

1. **It does nothing during suspend.** Go's timers ride `CLOCK_MONOTONIC`, which does not advance across Android suspend. Post-Doze the 8 s is counted from CPU resume, not from when the link died. It converts "30 s after wake" into "8 s after wake". It does **not** hold a carrier NAT mapping through deep sleep — no keepalive can, because the CPU is frozen.
2. **Server-side `max_idle_timeout` below 8 s** makes it a partial no-op (the negotiated value is the min). No server visibility. Unlikely to matter — servers rarely go below 10 s.
3. **False teardowns on a lossy link.** At 8 s idle / 3 s keepalive you tolerate roughly two consecutive lost PING round-trips. A cell link with a multi-second stall will tear down a connection that would have recovered, killing every in-flight app TCP stream over it. This is the reason the value is **8, not the 6 the Go expert proposed and not the 4 the bounds allow.** Reliability is priority #1 and the cost of being wrong in this direction is user-visible.
4. **★ The zombie-tunnel regression — the one that can actually hurt you.** `StopXray` never touches the pool (`xray/xray.go:106-115`), and `clientManager.clean()` closes an entry **only if it is already `StatusInactive`** (**VERIFIED**, `hysteria/dialer.go:332-339`: `if c.status() == StatusInactive { c.close() }`). Today a stranded client dies of its own 30 s idle timeout and is reaped. **With keepalive enabled, a stranded client PINGs forever, never goes inactive, and is never reaped** — a live tunnel on a protected (tun-bypassing) socket, draining battery and holding a server session, for the life of the process. Entries are never deleted from the map either (`:428-435` has no `delete`).

**Consequence of (4), and it is a hard design constraint:** *every* path that stops or reconfigures xray without immediately reusing the same pool entry **must kill the process.** In-process `runRedial()` is safe because it re-dials the same `dest.NetAddr()` and therefore *reuses* the same `*client`. A config change, a server change, or a user disconnect is **not** safe in-process. This is exactly the owner's chosen design, so A and the restart tier are coherent — but adopting the keepalive without enforcing the process kill on those paths is a net regression. Change 8 enforces it.

### Lever B — pool-key rotation (the only path to fork-equivalent latency; ship LAST, behind measurement)

This is the one idea that collapses the 8 s floor to ~250 ms without touching Go, and the Go expert's reopening of it is sound where the analysis's D12 rejection was not.

**Mechanics (VERIFIED).** The pool key is `dest.NetAddr()` (`hysteria/dialer.go:446`) = `Address.String() + ":" + Port`. `DomainAddress` stores the string **verbatim** — no lowercasing, no normalisation (`common/net/address.go:79-96, 129-131`). So `Vpn.example.com:443` and `vpn.example.com:443` are distinct pool keys for the same endpoint, and DNS is case-insensitive.

**It works even for a bare-IP server** — which is what retires D12's "there is nothing to alternate". Set `sockopt.domainStrategy` and xray resolves the domain with its **own** DNS client, honouring `dns.hosts` (`transport/internet/dialer.go:252-269` → `LookupForIP`). That resolution happens **inside `DialSystem`**, i.e. *after* hysteria has already computed the key at `:446`. So `a1.otc:1935`, `a2.otc:1935`, … mint distinct keys, all resolving to `78.17.84.51` with zero DNS traffic.

SNI is unaffected: `hysteria/dialer.go:457` calls `tlsConfig.GetTLSConfig()` with **no** destination option, so `ServerName` comes solely from `tlsSettings.serverName` (`transport/internet/tls/config.go:415-417`). Set it explicitly.

**★ Lever B and Lever A's keepalive are mutually exclusive. This is the trap.** Rotation abandons the old entry. With keepalive on, the abandoned entry never goes inactive and is never reaped — you accumulate a live zombie tunnel *per rotation*, permanently. That is strictly worse than the bug being fixed.

The combination that is actually safe is **rotation + no keepalive + default 30 s idle timeout**: the abandoned entry has no traffic, idle-times-out in 30 s, and is reaped by the 30 s `Periodic` (`:477-483`) within ~60 s. Bounded at 2–4 stale entries with a fixed ring, each self-clearing. Recovery latency becomes a fresh dial, ~250 ms — fork-equivalent. Use a **fixed ring of 2–4 aliases, never a monotonic counter** (map keys are never deleted, so unbounded rotation is an unbounded map).

**Why it is not the primary recommendation:** it requires `dns.hosts` + `domainStrategy` plumbing through `XrayConfigSanitizer`, it is the one item here not verified end-to-end on device, and getting the keepalive interaction wrong produces a silent battery-and-session leak. Ship Lever A first, measure, then evaluate B against real T2 firing rates.

### The ladder

Single `Mutex`. Critical section `NonCancellable`. `PARTIAL_WAKE_LOCK` (20 s timeout) held across the whole thing, released in `finally`. Screen-state-independent throughout.

```
trigger  (validated-capability change | link-props change on current underlying
          | device-idle exit | screen-on | 2× keepalive failure)
  → acquire wakelock + ladder mutex, NonCancellable
  → T0  refreshUnderlyingFromSystem() + applyUnderlyingNetworks() + honest probe      ~50 ms
       ↓ dead
  → T1  runRedial(): stopXray → setTunFd(freshDup) → start.  Tun untouched.
        Probe at 0.5 / 1 / 2 s.                                                        ~250-400 ms
       ↓ dead
  → T2  IDLE-TIMEOUT WAIT — the no-fork tax tier.
        Probe every 1 s to 12 s; one more runRedial() at 9 s.  Tun untouched.          up to ~12 s
       ↓ dead
  → T3  Process restart: persist → alarm → stopSelf → killProcess.                     ~1.2-2.0 s
       ↓ dead → exponential backoff to 60 s, cap 3 full passes, terminal Error to user
  → release wakelock in finally; record recovery success ONLY on a confirmed-OK probe
```

**Why T2 exists and why it precedes T3.** T1 fails whenever the QUIC connection is dead-but-not-yet-timed-out: `dial()` sees `StatusActive`, hands back the corpse, and no `protectFd` fires. T2 is simply "wait for `MaxIdleTimeout` to fire, then re-dial". It is slow (up to 12 s) but it has a **zero leak window** — the tun stays up, so traffic entering it is *blackholed, not leaked*. T3 is 6× faster but costs ~1.0–1.6 s of traffic in the clear. Under a reliability-and-security-first mandate, **blackholed-and-slow beats clear-and-fast**, so T2 goes first. This ordering is the single most important consequence of not forking.

**Why T2's budget is 12 s and not 8 s.** 8 s is the negotiated idle timeout; the timer only advances while the CPU is awake, and the probe itself has latency. 12 s gives ~1.5× headroom. Tune from the field histogram (Change 1's log line), do not guess.

**The old T2 (full in-process rebuild: `stopTunnel()` + `runConnect()`) is deleted.** It rebuilds the tun — reintroducing the establish-window that made the probe lie and a leak window — while doing **nothing** about the pool, which is the actual failure. It was never a recovery tier for this failure mode; it only ever helped tun-side corruption, which T3 also covers. Removing it shortens the ladder and removes a leak.

### Folded in from the original plan (fork-independent, still required)

| Item | State | Change # |
|---|---|---|
| Honest probe (`connect()` + `localAddress` assert + txid/QR) | **Done** — `DeltaVpnService.kt:517-547` | — |
| Idempotent `registerProtectControllers` | **Done** — `PlatformXrayEngine.android.kt:180` | — |
| Trigger set: validated-caps / link-props / idle-exit / screen-on / keepalive, screen-state-independent | **Mostly done**; remove the screen-off gate, keep keepalive loop running slowed | 6 |
| Register network callback **once** per process, never unregister until `onDestroy` (100-request per-UID cap) | **To do** | 7 |
| Recovery owned by `:vpn`; delete the cross-process broadcast | **To do** | 4 |
| Split user-disconnect from recovery-restart so params are not cleared | **To do** | 8 |
| Keep the tun (`runRedial`, never `stopTunnel`); leak the dup'd fd | **To do** | 3 |
| Wakelock + single mutex + `NonCancellable` | **Partial** (mutex exists) | 5 |
| `recordRecoveryKill()` only *after* confirmed success | **To do** | 5 |
| `onRevoke()` terminal, not recoverable | **To do** | 8 |
| Delete false doc comments (`:60-63`, `:227-231`, `:290`, `:573-577`) | **To do** | 3 |

---

## Restart tier — exact mechanics

T3 only. Built to be correct, on the assumption it fires rarely.

### What actually grants the foreground-service start (write this down)

**Three independent grants exist, and the alarm is not one of them.** The Android expert's finding here is correct and non-obvious:

- **`REASON_OP_ACTIVATE_VPN`** — a consented `VpnService` app is *unconditionally* exempt from the FGS background-start restriction. `ActiveServices.java:8884-8892` checks `AppOpsManager.checkOpNoThrow(OP_ACTIVATE_VPN, callingUid, callingPackage) == MODE_ALLOWED`; consenting to a `TYPE_VPN_SERVICE` VPN sets that op (`Vpn.java:1349-1365`). **This exemption is absent from the public docs.**
- **`REASON_SYSTEM_ALLOW_LISTED`** — via the battery-optimization allowlist (`ActivityManagerService.java:6497-6503` → `FAKE_TEMP_ALLOW_LIST_ITEM` → `ActiveServices.java:8804-8807`).
- **Not the alarm.** In our exact configuration (battery-allowlisted, *without* `SCHEDULE_EXACT_ALARM`), `AlarmManagerService.java:2760` clears `FLAG_ALLOW_WHILE_IDLE`, so `:2837`'s `idleOptions = allowWhileIdle ? mOptsWithoutFgs.toBundle() : null` evaluates to **`null`** — no temporary power allowlist, no FGS capability from the alarm at all. It still works, but for a reason nobody would guess. Do not reason "the alarm confers the FGS start."

These are **INFERRED from AOSP source read by the Android expert, not from this device.** They are load-bearing, so Change 11 pairs them with a field log line.

### Quota and permission (INFERRED — AOSP read, verify per Change 11)

- `canScheduleExactAlarms()` returns true for a battery-allowlisted app **without** `SCHEDULE_EXACT_ALARM` — `AlarmManagerService.java:2869-2871` → `isExemptFromExactAlarmPermissionNoLock` (`:2689-2695`) → `DeviceIdleController.isAppOnWhitelist`.
- A battery-allowlisted app is **fully exempt from the allow-while-idle quota** (`:2753-2761` sets `FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED`; `:2443-2445` then treats it as unrestricted). This contradicts Google's public Doze doc, which still claims the ~9-minute rule applies to allowlisted apps. **Requires `workSource == null`** — use plain `setExactAndAllowWhileIdle`, never a `setWithWorkSource` variant.
- The analysis's D10(i) ("~1 per 9-10 min in deep Doze") is stale by four releases; the current quota is 72/hour (`:762-764`), and it does not apply to us at all.

**Permission decision:** declare `SCHEDULE_EXACT_ALARM` as a **belt-and-braces fallback only** — it covers the user later revoking the battery-optimization exemption, at the cost of a Play declaration form (defensible for a VPN). Do **not** declare `USE_EXACT_ALARM`: it is Play-policy-restricted to alarm-clock/calendar/timer apps and a VPN declaring it is a plausible rejection. Always wrap the call in a `SecurityException` catch anyway.

**Rejected:** `setAlarmClock` — unrestricted and wakes early, but renders a **user-visible status-bar alarm icon** and appears in Clock. Unacceptable for a VPN reconnect.

Use `ELAPSED_REALTIME_WAKEUP`, never `RTC_*` — RTC alarms move when NTP corrects the clock after a Doze exit.

### Manifest additions

`WAKE_LOCK` (`:13`), `FOREGROUND_SERVICE_SPECIAL_USE` (`:7`) and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`:9`) are already present. Add:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- Fallback only. The battery-optimization allowlist already grants exact alarms via
     AlarmManagerService#isExemptFromExactAlarmPermissionNoLock; this covers the user
     revoking that exemption. Play console declaration required. -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

and, inside `<application>`:

```xml
<!-- android:process=":vpn" is load-bearing: it keeps the main process off the restore path. -->
<receiver
    android:name=".vpn.BootRestoreReceiver"
    android:exported="false"
    android:process=":vpn"
    android:directBootAware="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

**No receiver is used for the restart itself.** `PendingIntent.getForegroundService()` with an explicit `ComponentName` lands directly in `:vpn` — no receiver hop, no main-process cold start, no `START_STICKY`. A manifest receiver would add a component-start hop and (in the default process) a main-process cold start, for nothing.

`BOOT_COMPLETED` is safe for us despite Android 14+'s FGS-type restrictions from that broadcast (`ActiveServices.java:1179-1182`, change id `296558535`): `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` is in the default allowlist (`ActivityManagerConstants.java:200-206`), and because we are battery-allowlisted the grant resolves to `REASON_SYSTEM_ALLOW_LISTED` before that type check is even reached.

### The restart call, exactly

```kotlin
// core/vpn/impl/src/androidMain/.../DeltaVpnService.kt — T3 path only. ORDER IS LOAD-BEARING.
private fun restartProcessForRecovery(reason: String) {
    // 1. Persist FIRST. commit(), not apply() — this process is about to be SIGKILLed.
    paramsStore.save(ConnectionParams(activeXrayJson!!, activeDisallow, activeAllow))
    val gen = recoveryPrefs().getInt(KEY_RESTART_GEN, 0) + 1
    recoveryPrefs().edit()
        .putInt(KEY_RESTART_GEN, gen)
        .putLong(KEY_LAST_RESTART_AT, SystemClock.elapsedRealtime())
        .commit()
    logd("T3 restart: reason=$reason gen=$gen")

    // 2. Explicit component → lands directly in :vpn. FLAG_IMMUTABLE mandatory since Android 12.
    val intent = Intent(this, DeltaVpnService::class.java)
        .setAction(ACTION_CONNECT)
        .putExtra(EXTRA_RESTART_REASON, reason)
    val pi = PendingIntent.getForegroundService(
        this, REQ_RESTART, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val at = SystemClock.elapsedRealtime() + RESTART_DELAY_MS   // 500
    val am = getSystemService(AlarmManager::class.java)
    try {
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    } catch (e: SecurityException) {
        logd("T3: exact alarm denied (${e.message}) — falling back to inexact")
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    // 3. stopSelf() BEFORE the kill: clears AMS's "wants restart" bookkeeping so START_STICKY
    //    does not race our alarm for the same component (D9's ×4 SERVICE_RESTART_DURATION
    //    escalation). Already understood in the tree — see the comment at :322.
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()

    OtcLog.flushBlocking()          // finalize the log file before the hard kill
    Process.killProcess(Process.myPid())
}
```

**Four non-obvious requirements:**

1. **`stopSelf()` before `killProcess`.** Without it AMS *also* schedules a `START_STICKY` restart that races the alarm for the same component, reintroducing the ×4 backoff escalation.
2. **Return `START_NOT_STICKY`** from `onStartCommand` once this lands. It is `START_STICKY` today (`DeltaVpnService.kt:171`); with an alarm engine, sticky is pure liability. **Keep the `intent == null` self-heal branch** (`:157-170`) — it still fires on a genuine system kill and is free.
3. **`RESTART_DELAY_MS = 500`, and do not shorten it.** Non-zero so the process actually dies first; if the alarm fires into a still-terminating process, AMS may deliver `onStartCommand` to a corpse and the restart is silently lost. **INFERRED — tune under stress test.** The trade is asymmetric: a lost restart is an *unbounded* leak, strictly worse than 500 ms of bounded leak. Do not optimise the wrong side.
4. **A persisted restart-generation counter, `commit()`ed.** Enforces backoff *across process deaths* — otherwise each fresh process starts with a clean debounce and can hot-loop. Note `elapsedRealtime()` survives process death and resets at reboot, which is the correct semantic here (the existing `recordRecoveryKill` at `:453-457` already gets this right).

### Leak minimisation, in order of effectiveness

1. **Never take this path.** The leak is per-restart, so restart *frequency* dominates. T0–T2 are all zero-leak; T3 should approach never. This is the whole reason T2 precedes T3.
2. **Always-on VPN + lockdown** — the only true fix, and the only mechanism that makes the gap fail *closed* (lockdown installs UID-range blackhole rules while no VPN is up). Not app-grantable: `DevicePolicyManager.setAlwaysOnVpnPackage(..., lockdown = true)` requires Device Owner / Profile Owner. Recommend in-app via `Settings.ACTION_VPN_SETTINGS`, once, after the first successful connection, dismissible, never modal.
3. **Establish the tun *before* starting xray** on the restart path (already the order at `:214-231`). Routes are captured and blackholed by the tun while xray dials — converts ~250 ms of *clear* traffic into ~250 ms of *blackholed* traffic. Correct trade for a VPN.
4. **Do not shorten `RESTART_DELAY_MS`** to chase the remaining milliseconds. See requirement 3 above.

**Detection of always-on is not available.** There is no public API; `Settings.Secure` key `always_on_vpn_app` is `@hide` and per-user. **UNVERIFIED** whether a third-party read succeeds on Android 16 — test with `adb shell settings get secure always_on_vpn_app` and `Settings.Secure.getString(cr, "always_on_vpn_app")`, expecting `null`. **Design the UI so `null` means "unknown", never "not enabled"** — nagging a user who already enabled it is the failure mode.

---

## Honest reliability & latency expectations

### The headline, stated without spin

**This design is 8–30× slower than the fork-based design in its common case, and the gap is structural, not incidental.** The fork's `CloseAll()` cancelled `c.conn.Context()` on demand, making every recovery a ~250 ms fresh dial. Without it, recovery latency is gated by an 8 s timer that only advances while the CPU is awake. No amount of app-side engineering changes that. Lever B is the only route back to ~250 ms, and it is unverified and carries the zombie-entry trap.

### Latency is bimodal, and that matters more than the average

The two modes have different mechanisms and should be measured separately:

- **Hard handover** — the old network is torn down, so `sendmsg` returns `ENETUNREACH`/`EHOSTUNREACH`. `send_queue.go:91-99` treats that as fatal → `destroyImpl` → context cancelled **immediately**. T1 succeeds in ~250–400 ms. *(INFERRED — this is the mechanism the fd-shutdown idea was trying to induce artificially; here the kernel supplies it for free. Verify with Change 1's `protectFd` counter.)*
- **Soft handover / silent blackhole** — both networks briefly up, or a NAT rebind, or a server-side path rejection. Writes keep succeeding into the void. Nothing errors. **The 8 s idle timeout is the only exit.** T2 territory.

The ratio between these two modes in the field is the single most important unknown in this document, and Change 1 measures it directly.

### Per scenario

| Scenario | Recovery latency (no-fork) | Reliability | What the fork would have given |
|---|---|---|---|
| **Wi-Fi ↔ cell, screen-on** | **hard:** ~0.3–1.5 s (trigger delay + T1)<br>**soft:** ~9–13 s (trigger + T2 idle-timeout wait) | **~97 %** | ~0.3–0.8 s, both modes |
| **Handover, screen-off (not yet dozing)** | same as above; callbacks are normally delivered | **~93 %** — callback delivery is less certain, and the keepalive loop's `delay()` does not advance during suspend | same as above |
| **Doze exit** | **~8–11 s from the idle-exit broadcast.** Timers frozen during suspend, so the 8 s is counted from CPU resume — never from when the link actually died | **~90 %** | ~1 s from the wake event |
| **Deep Doze, tunnel dead, no wake event** | unbounded — dead until the next idle-exit / screen-on | n/a | identical; the fork does not help here either |
| **T3 fires (all in-process tiers exhausted)** | ~1.2–2.0 s, plus **~1.0–1.6 s of traffic in the clear**; add 1–4 s if the cellular radio must re-attach | **~97 % stock AOSP, ~85–90 % HyperOS** | T3 essentially never fires with the fork |

Reliability figures are **INFERRED** — they are the product of trigger delivery, probe accuracy, and `:vpn` surviving. They are not measured. Change 1 makes them measurable.

### Exactly where this is worse than the fork-based design

1. **Soft-handover and Doze-exit recovery take ~8–12 s instead of ~250 ms–1 s.** This is the main cost and it is unavoidable without a fork or Lever B.
2. **T2 exists at all.** It is a pure "wait out a timer" tier — a delay, not a repair. The analysis correctly rejected exactly this shape as a *primary* tier (D8); it is tolerable here only because the tun stays up, so the cost is blackholed traffic rather than leaked traffic or a dead tunnel believed alive.
3. **T3 will occasionally fire, and it leaks.** With the fork it was designed never to run. Every T3 firing is ~1.0–1.6 s of unprotected traffic on a recovery the user never asked for.
4. **In-process config changes become illegal.** `dialer.go:449-465` refreshes only `c.setCtx(ctx)` on a pool hit — `config` (incl. `Auth`), `tlsConfig`, `socketConfig` and `quicParams` were all captured at first insert and are never refreshed. **An in-process re-dial can never pick up a new config for the same `host:port`, not even after `StopXray` + `RunXrayFromJSON` with new credentials.** The fork's `CloseAll()` fixed this as a side effect. Without it, every config/server/auth change **must** go through a process restart. This is a correctness trap independent of reconnect, and Change 8 is what keeps it from biting.
5. **The keepalive introduces a zombie-tunnel failure mode that did not previously exist** (see Lever A, adversarial item 4). It is fully mitigated by Change 8, but it is new attack surface that the fork would not have created.

### What is *not* worse

Zero-leak recovery at T0–T2, the honest probe, single-process recovery, and the trigger set are all fork-independent. The 22.2 s unprotected-traffic window is eliminated in both designs.

### The option not taken, and why it is worth revisiting

The systems expert's **`:xray` process split** — move libXray into its own bound service, keep the tun in `:vpn`, kill and rebind `:xray` to evict the pool — delivers fork-equivalent semantics (~300 ms, zero leak, no upstream debt, and a Go panic stops being VPN death) with **no Go changes**. It was not selected because it is a *larger one-time refactor* than the fork it replaces (AIDL boundary, tun fd over Binder, and a per-socket Binder hop for `protect()` — `VpnService.protect()` is an instance method and the direct same-UID call from `:xray` is **UNVERIFIED**). Given the owner's complexity objection, this is deferred rather than rejected: **if field data shows T2 or T3 firing often, this is the correct next move, ahead of both Lever B and the fork.**

---

## Ordered implementation spec

Strictly ordered. Change 1 ships alone, first — nothing after it is measurable until it lands.

---

### Change 1 — Instrumentation (ship alone, one build)

**Files:** `core/xray/src/androidMain/.../PlatformXrayEngine.android.kt` (`ProtectFdInvocationHandler.invoke`, `:221-240`); `core/vpn/impl/src/androidMain/.../DeltaVpnService.kt` (ladder + probe).

1. **`protectFd` counter.** In the invocation handler, increment a process-global `AtomicLong` and log `protectFd #<n> fd=<fd>`. This is the eviction oracle: **a recovery with no `protectFd` increment did not get a fresh upstream connection.**
2. **Log the emitted `runtimeJson` once per process.** The `runtimeJsonLogged` flag already exists (`DeltaVpnService.kt:81-82`) — confirm it actually emits. Settles whether `streamSettings.finalmask` exists today (insert vs. merge for Change 2).
3. **Log ladder tier entry/exit with elapsed ms and the `protectFd` count at each boundary.**
4. **Log `PowerManager.isIgnoringBatteryOptimizations` on every recovery.** It is load-bearing for three separate grants (exact-alarm eligibility, quota exemption, FGS start); a silent revocation changes the failure mode entirely and would otherwise be invisible.

**Acceptance check:** field run — handover both directions, plus a Doze exit after ≥ 10 min screen-off. Confirm every recovery logs a tier trace and a `protectFd` delta. **This run also produces the hard-vs-soft handover ratio** that determines whether Lever B is worth building.

**Verifies:** the "hard handover errors out immediately" inference (a T1 success with a `protectFd` increment inside ~400 ms of a handover confirms it).

---

### Change 2 — Lever A: `quicParams` into the emitted config

**File:** `core/xray/src/commonMain/kotlin/com/onthecrow/deltavpn/xray/XrayConfigSanitizer.kt`

Inject `streamSettings.finalmask.quicParams: { maxIdleTimeout: 8, keepAlivePeriod: 3 }` into each hysteria outbound. **MERGE, never overwrite** — `share/hysteria_mask.go:12` only allocates `quicParams` when the share link carries bandwidth/ports, and clobbering it silently downgrades brutal congestion control to BBR. Merge at the `quicParams` object level: preserve every existing key, set only these two.

The sanitizer currently emits no `finalmask` at all (grep confirms), so this is new-object creation in the common case — but the merge path must be correct for links that do carry it.

**Acceptance check:** grep the Change-1-logged `runtimeJson` for `"maxIdleTimeout":8`. Then confirm the config **parses** — validation at `infra/conf/transport_internet.go:1951-1955` hard-fails out-of-range values, so temporarily emitting `maxIdleTimeout: 3` and observing `runXrayFromJSON` fail with "MaxIdleTimeout must be between 4 and 120" is a *positive* proof the field is being read rather than silently ignored. **Do this once; it is the only way to distinguish "parsed" from "passed through unknown".**

**Field verification:** post-Doze recovery logs a fresh dial ~8 s after the idle-exit line rather than ~30 s. Log line: `T2: idle-timeout wait — protectFd delta=1 after <n>ms`.

⚠️ **UNVERIFIED until the acceptance check passes:** that this app's config generator routes through `finalmask` at all. An unknown key inside `finalmask` would look identical to success without the deliberate-failure test.

---

### Change 3 — `runRedial()`: keep the tun

**File:** `core/vpn/impl/src/androidMain/.../DeltaVpnService.kt`

1. Add `runRedial()` = `stopXray()` → `setTunFd(tunInterface.dup().detachFd())` → `xrayEngine.start(activeXrayJson)`. It **never** calls `stopTunnel()`. `runConnect` / `stopTunnel` remain for user connect, user disconnect, revoke and fatal failure only.
2. **Do not close the dup'd fd** in `stopXray()` (`:333-338` currently does). `AndroidTun.Close()` is a verified no-op (`tun_android.go:47-49`) and `core.Instance.Close()` does not join the gVisor `fdbased` reader goroutines, while the logs show fd numbers recycling deterministically (`101/102/103`). Leak one fd per redial — bounded by recovery count, reclaimed at process death. A timed delay before closing is a race you cannot prove you won.
3. **Always `setTunFd(freshDup)` before every `start()`, and log the number.** The env var `xray.tun.fd` persists for the process lifetime (`xray/xray.go:51-53`, read lazily at `tun_android.go:28`); combined with (2), forgetting to re-set it fails **silently** on a stale-but-still-open fd. Assert the logged number strictly increases.
4. **Delete the false doc comments** at `:60-63` ("no in-process soft re-dial anymore… KILLS this :vpn process"), `:227-231`, `:290`, and correct `:573-577` (`setUnderlyingNetworks` writes `NetworkAgent` metadata only — it sets no fwmark and touches no routing table; a `protect()`ed socket follows the system default network, always). These comments caused two of three experts to misread the current behaviour; leaving them will do it again.

**Acceptance check:** 20 consecutive recoveries produce **zero** `tun established` log lines. `adb shell ls -l /proc/<vpn-pid>/fd | wc -l` grows monotonically by ≤ 1 per redial with no reuse of a live number.

---

### Change 4 — Recovery lives entirely in `:vpn`

**Files:** `core/vpn/impl/src/androidMain/.../VpnStatusBroadcast.kt:45-59`; `core/vpn/impl/src/androidMain/.../PlatformVpnController.android.kt:47-77`; `DeltaVpnService.kt:439-448`.

Delete `sendRecoverRequest` / `registerRecoverRequest` and `onRecoverRequested` / `reconnect`. `recover()` calls the local ladder directly. The main process becomes a UI mirror only; status broadcasts to it remain best-effort and their 39–68 s deferral becomes cosmetic rather than fatal.

**Acceptance check:** `adb shell am kill com.onthecrow.deltavpn.dev` (kills main, leaves the `:vpn` FGS), then toggle Wi-Fi. Recovery completes with **no** `CTRL` log line. `send recover request` and `recover request ignored — no cached config` never appear again.

---

### Change 5 — The ladder

**File:** `DeltaVpnService.kt` (replaces `startTunnelJob` / `recover`, `:415-457`)

Implement T0 → T1 → T2 → T3 as specified above. Requirements:

- Single `Mutex` (reuse `operationMutex`), critical section `NonCancellable`.
- `PARTIAL_WAKE_LOCK`, 20 s timeout, acquired at ladder entry, **released in `finally`**.
- **Move `recordRecoveryKill()` to after a confirmed-successful recovery**, so a failed attempt does not burn the debounce token.
- Attempt budget: 3 full passes, exponential backoff to 60 s, then a terminal `Error` state surfaced to the user. A correct probe still cannot distinguish "upstream dead" from "server down" from "credential rejected" — do not hot-loop against a genuinely down server.
- Probe timeout drops `1500 → 600 ms`: a needless redial now costs ~250 ms, whereas 1.5 s of dead traffic at unlock is user-visible.
- **Never probe within `PROBE_ESTABLISH_GRACE_MS` of an `establish()`** (the constant already exists, `:74-78`) and never treat a probe issued before the first post-establish network callback as authoritative.

**Acceptance check:** Wi-Fi→cell screen-on, 20/20 runs recover. Log shows `T0 dead → T1 → probe OK localAddress=10.77.0.2` with a `protectFd` delta ≥ 1. Airplane-mode 30 s on/off: backoff engages, no hot-loop, no terminal `Error` for a recoverable case.

---

### Change 6 — Remove the screen-off gate

**File:** `DeltaVpnService.kt:592-606`

Stop cancelling `tunnelJob` on `ACTION_SCREEN_OFF`; **slow the keepalive loop to ~60 s** instead. A handover with the screen off is exactly the case that must work, and post-Change-3 it costs ~250 ms of CPU under a wakelock. Keep `ACTION_SCREEN_ON` as a fast path alongside the idle-exit receiver.

This is best-effort by construction — `delay()` rides `CLOCK_MONOTONIC` and does not advance during suspend. The goal is to *bound* the dead window at ~60 s of awake time, not to eliminate it. **The tunnel dying during deep Doze is expected and acceptable**; the failure mode being eliminated is *dead but believed alive*.

**Acceptance check:** handover with screen off, 10/10 recover with no `SCREEN_ON` in the log between trigger and recovery.

---

### Change 7 — Register the network callback once per process

**File:** `DeltaVpnService.kt:483, 492-506` (`startMonitoring` / `stopMonitoring`)

Register before `startXrayOnTun`, never unregister until `onDestroy`. AOSP enforces a per-UID cap of 100 concurrent `NetworkRequest`s and throws `TooManyRequestsException` past it — a real ceiling in a long-lived `:vpn` process doing in-process recovery, made worse because `stopMonitoring()` currently swallows unregister failures in `runCatching { }`.

**Acceptance check:** 100 consecutive redials, no `TooManyRequestsException`; exactly one `registerNetworkCallback` line per process lifetime.

---

### Change 8 — Split user-disconnect from recovery-restart ★

**File:** `DeltaVpnService.kt:339-370` (`runDisconnect`)

Split into:

- **`runUserDisconnect()`** — the **only** caller of `paramsStore.clear()` (`:344`). Emits `Disconnecting` → `Disconnected`, stops the tunnel, then `scheduleProcessDeath()`.
- **`runRecoveryRestart()`** — keeps the persisted params, keeps the status at `Connecting`, and **never emits `Disconnected`**.

**Why `Disconnected` during recovery is actively harmful:** besides destroying recovery's own fallback config, that status drives `VpnSyncWorker` (`feature/connection/logic-impl/.../VpnSyncWorker.kt:66-76`) to reset `activeKey` — so a genuine remote config change arriving during a recovery is **silently swallowed**. `VpnSyncWorker` is a fourth actor on the tunnel that no expert enumerated.

**Enforce the process-kill invariant here.** Because of the zombie-tunnel risk (Lever A, item 4) and the stale-capture trap (`dialer.go:449-465`), **every config change, server change and user disconnect must go through a process kill.** Concretely: any path that changes `activeXrayJson` to a different value, or stops the tunnel without an immediate same-endpoint redial, terminates in `scheduleProcessDeath()`. Add an assertion + log line if `runRedial()` is ever called with an `activeXrayJson` differing from the one xray was started with.

**Handle `onRevoke()` as terminal** (`:176-179`) — another VPN app or the user in Settings revoking us is not a recoverable network failure. Today it is unmodelled and routes into `runDisconnect`.

**Acceptance check:** `paramsStore.clear()` appears in the log **only** on a user disconnect, never on a recovery. Config switch: the new auth is actually used (verify a changed credential reaches the server — this is the `dialer.go:449-465` capture bug, and it is only fixed by the process kill).

---

### Change 9 — T3 restart engine

**Files:** `androidApp/src/main/AndroidManifest.xml`; `DeltaVpnService.kt`; new `core/vpn/impl/src/androidMain/.../BootRestoreReceiver.kt`

Manifest additions and the `restartProcessForRecovery` body exactly as specified in "Restart tier — exact mechanics". Plus:

- Change `onStartCommand`'s return from `START_STICKY` (`:171`) to `START_NOT_STICKY`. **Keep the `intent == null` self-heal branch** (`:157-170`).
- `BootRestoreReceiver` in `:vpn`: on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, load `ConnectionParams` and `startForegroundService(ACTION_CONNECT)` if params exist.
- Schedule **one** persisted `JobScheduler` job (`setRequiredNetworkType(NETWORK_TYPE_ANY)`, `setPersisted(true)`) as a last-ditch backstop for a lost alarm (reboot race, OEM alarm purge). Treat it as "eventually", not "reliably" — Doze defers `JobScheduler` to maintenance windows, and expedited work bypasses App Standby quotas, **not Doze**.

**Acceptance check:** force T3 (stub the probe to always fail). Restart completes in < 3 s, `protectFd` increments, params survive. Then `adb shell dumpsys deviceidle force-idle` and force T3 again — confirm it still fires (this is the quota-exemption claim under test).

---

### Change 10 — In-app always-on VPN recommendation

**File:** main-process UI

Deep-link `Settings.ACTION_VPN_SETTINGS`, shown once after the first successful connection, dismissible, never modal. Frame as "Block connections without VPN". On `Build.MANUFACTURER` Xiaomi, add Autostart and "battery saver: No restrictions" as checklist items.

⚠️ **Detection is UNVERIFIED.** Test `Settings.Secure.getString(cr, "always_on_vpn_app")` on Android 16; expect `null`. **`null` must render as "unknown", never as "not enabled".**

**Acceptance check:** the prompt does not reappear after dismissal, and never appears more than once per install.

---

### Change 11 — Field verification of the AOSP inferences

Everything in "Restart tier" derived from AOSP source is **INFERRED** — read from `aosp-mirror/platform_frameworks_base`, not from this device. Pair each with a log line:

| Inference | Log line that verifies it |
|---|---|
| Battery-allowlisted app gets exact alarms without `SCHEDULE_EXACT_ALARM` | `T3: exact alarm scheduled (canScheduleExactAlarms=<b>, ignoringBatteryOpt=<b>)` — and the absence of the `SecurityException` fallback line |
| Allowlisted apps are exempt from the allow-while-idle quota | Force 5 T3 restarts inside 10 min under `force-idle`; all 5 fire on time |
| FGS start is granted via `OP_ACTIVATE_VPN` | T3 succeeds with `ignoringBatteryOpt=false` (revoke it deliberately once) |
| `udp_sendmsg` ignores `sk_shutdown` | **Not needed** — the idea is dropped |
| Hard handover produces an immediate write error | `T1 success protectFd delta=1 elapsed=<n>ms` within ~400 ms of a hard handover |

**This table is the definition of done for the restart tier.** Do not consider T3 shipped until every row has a real log line from a real device.

---

## Residual risks

Ranked by expected damage.

1. **★ The 8 s floor is simply too slow for the soft-handover case, and the field data shows T2 firing constantly.** This is the design's central bet. If Change 1's field run shows soft handovers dominating, the ladder spends most recoveries in a 9–13 s wait. **Mitigation path, in order:** Lever B (alias rotation, ~250 ms, but mutually exclusive with keepalive — see the trap above), then the `:xray` process split, then reconsider the fork. Do not tune `maxIdleTimeout` below 8 to paper over this; that trades a latency problem for false teardowns on lossy links.

2. **The zombie-tunnel regression** — keepalive keeps a stranded pool entry `StatusActive` forever, and `clean()` only reaps `StatusInactive` entries (`dialer.go:332-339`), and entries are never deleted from the map (`:428-435`). A live tunnel on a protected socket, draining battery and holding a server session, invisible to every existing log line. Fully mitigated by Change 8's process-kill invariant — **but that invariant is a discipline, not a mechanism, and the next person to add a config-change path will not know about it.** Add the assertion in Change 8 and a comment at the `quicParams` injection site pointing here.

3. **A Go panic in xray is uncatchable `:vpn` process death**, and every in-process tier increases how often the risky paths execute. `START_NOT_STICKY` (Change 9) means AMS will not restart us; the alarm was never scheduled because the panic was not a planned restart. **The `intent == null` self-heal branch is the only net here, and it depends on AMS restarting the service, which Change 9 disables.** Resolve this explicitly: either keep a `JobScheduler` backstop that notices a dead tunnel (Change 9's persisted job), or accept the exposure. *This is a real gap in the design as specified and should be closed before shipping Change 9.*

4. **OEM kills of `:vpn` (HyperOS/MIUI).** Nothing app-side beats an OEM that decides to kill a foreground service. Only always-on VPN + lockdown genuinely covers it, and it is a user setting with no programmatic grant and no public read API. T3 is the app-side backstop and it is deliberately weak — `~85-90 %` on HyperOS.

5. **`quicParams` not actually reaching the emitted config** (Change 2, ⚠️ unverified). An unknown key inside `finalmask` passes silently and looks identical to success. The deliberate out-of-range test is the only proof. **If this fails, Lever A does not exist and the design falls back to a 30 s floor** — at which point T2's budget must widen to ~35 s and the ladder becomes materially worse than specified here.

6. **Server-side `max_idle_timeout` below 8 s** makes Lever A a partial no-op (negotiated value is the min). No server visibility. Low probability, easy to detect once Change 1 lands (T2 completing much faster than 8 s).

7. **A second cached layer nobody audited.** `http3.Transport` (`dialer.go:252-256`) holds its own connection state and no one proved `c.close()` tears it down completely. Separately, `transport/internet/quic` keeps its own `clientConnections` process-global with the same shape — **if vless-over-QUIC is ever in scope for this app, this entire analysis is transport-specific and does not cover it.**

8. **The probe still lying for an unmodelled reason** — Private DNS (DoT to `dns.google` from the resolver UID) or the `com.google.android.gms` split-tunnel exclusion producing local answers. The `localAddress` assertion is correct under all known mechanisms, but the unexplained `01:39:49 → 01:39:58` window in the original logs (probe healthy, then two failures on a just-verified tunnel) is still not fully accounted for.

9. **`RESTART_DELAY_MS = 500` is a guess.** If the alarm fires into a still-terminating process the restart is silently lost, and the resulting leak is unbounded. INFERRED; must be tuned under the stress test, and erring long is correct.

10. **MTU 1500 → 1400** (`:191`): proxied UDP near 1500 B cannot fit a QUIC DATAGRAM frame (quic-go clamps ~1200–1350), exposing QUIC-over-QUIC (browser HTTP/3), large DNS and WebRTC. Low priority, unconfirmed without a capture.

11. **IPv6 leaks today** (`:190-201` is IPv4-only; Android does not blackhole address families a VPN omits, and on cellular IPv6 is nearly always present with Happy Eyeballs preferring it). Add `addRoute("::", 0)` first so v6 fails **closed**; add an address and v6 DNS only after verifying v6 through the tun inbound and the hysteria outbound. **Ship separately, after the reconnect work is measured** — it will confound the field measurement, and if v6 egress is broken it costs a ~250 ms Happy-Eyeballs penalty per connection.

---

## Appendix — the three investigation reports (verbatim)

### A.1 — Go / quic-go report (fd-shutdown verdict)

> **Adjudicator's note:** §1–§3 of this report are confirmed. Its §6 Lever A is adopted as the primary fix (with the value raised 6 → 8) and its Lever B is adopted as a deferred accelerator. Its zombie-tunnel caveat is promoted to a hard design constraint (Change 8).

# Verdict on the fd‑shutdown idea

**DOES NOT WORK** — `shutdown(fd, SHUT_RDWR)` on this socket cannot tear the QUIC connection down, and its most likely effect is a 100 %‑CPU spin in the `:vpn` process. `close(fd)` is worse. **But the investigation found a better no‑fork lever that does work, is config‑only, and needs no fd games at all.**

---

## 1. Which sockets exist, and can we identify the QUIC one? (VERIFIED)

**The socket is created unconnected, via `ListenPacket`.**

- `hysteria/dialer.go:452` sets `dest.Network = net.Network_UDP` at pool insert, so `c.dest.Network == UDP` forever.
- `hysteria/dialer.go:172` → `internet.DialSystem(c.ctx, c.dest, c.socketConfig)`.
- `transport/internet/system_dialer.go:53` — `if dest.Network == net.Network_UDP && !hasBindAddr(sockopt)` → takes the **`lc.ListenPacket(ctx, "udp", "0.0.0.0:0")`** branch (`:80`). `hasBindAddr` (`:46-48`) is false unless `sockopt.bindAddress`+`bindPort` are set, which they are not in your config.
- Returns `*PacketConnWrapper{PacketConn: <*net.UDPConn>, Dest: destAddr}` (`:84-87`), which `hysteria/dialer.go:181-183` unwraps to the bare `*net.UDPConn`. That object is handed to `quic.DialEarly` at `hysteria/dialer.go:257` and stored as `c.pktConn` at `:318`.

So: **one long-lived, unconnected, `bind`-to-`0.0.0.0:0` UDP socket per pool entry.** All proxied app traffic (TCP and UDP alike) rides QUIC streams / QUIC datagrams over that same socket — `udpSessionManagerClient` (`hysteria/dialer.go:31-112`) multiplexes app UDP into QUIC datagrams; it does **not** create host sockets. Short-lived host sockets only appear for a `freedom` outbound or xray's own DNS.

**The discriminator is the problem.** libXray throws away the metadata:

```go
// .libxray-build/libXray/controller/controller_android.go:13-17
func RegisterDialerController(controller func(fd uintptr)) {
	xinternet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error {
		return conn.Control(controller)          // network, address DISCARDED
	})
}
```
`android_wrapper.go:10-11` — `ProtectFd(int) bool`. Kotlin sees **an int and nothing else** (`PlatformXrayEngine.android.kt:241-246`).

Worse, the `lc.Control` hook (`system_dialer.go:66-79`) runs **after `socket()` but before `bind()`**, so a `getsockname()` at protect time returns `0.0.0.0:0` — useless. You could only classify it *later*, at recovery time, via `ParcelFileDescriptor.fromFd()` + `Os.getsockoptInt(fd, SOL_SOCKET, SO_TYPE) == SOCK_DGRAM` + `getpeername() == ENOTCONN` + "oldest surviving UDP fd". That is a heuristic stack, not a guarantee — and it is moot, because of §2.

---

## 2. What `shutdown()` actually does inside quic-go (VERIFIED code path, INFERRED kernel step)

### The read path is a dead end — and it spins

Linux `inet_shutdown()` on an unconnected UDP socket returns `ENOTCONN` but still sets `sk->sk_shutdown` and wakes pollers (the "Hack to wake up other listeners" path). `datagram_poll()` then reports `EPOLLIN|EPOLLRDHUP`, and `__skb_wait_for_more_packets()` takes `out_noerr:` (`*err = 0`), so **`recvmsg`/`recvmmsg` returns a zero-length datagram, not an error.** (INFERRED — kernel source not in tree; this is the well-known UDP behaviour and must be confirmed on-device before relying on any read-path scheme.)

Feed that into quic-go and it does **not** error out:

```go
// sys_conn_oob.go:174-177
n, err := c.batchConn.ReadBatch(c.messages, 0)
if n == 0 || err != nil {
    return receivedPacket{}, err        // err == nil → returns an EMPTY packet, no error
}
```
```go
// transport.go:528-554
p, err := conn.ReadPacket()
...
if err != nil { t.close(err); return }
t.handlePacket(p)                        // err is nil, so we get here
```
```go
// transport.go:563-566
func (t *Transport) handlePacket(p receivedPacket) {
	if len(p.data) == 0 { return }        // silently dropped
}
```

**Result: an unbounded busy loop at 100 % CPU in `Transport.listen`, forever.** `lastPacketReceivedTime` is never touched (`handlePacket` returns before any connection state is reached), so the idle timer is unaffected — meaning **you burn a core and gain exactly zero milliseconds.** `basicConn.ReadPacket` (`sys_conn.go:94-110`) has the identical hole (`n=0, err=nil` → `data[:0]`), so wrapping with udpmask/udphop does not save you.

Note the irony: quic-go's *own* way of unblocking this reader is `t.conn.SetReadDeadline(time.Now())` (`transport.go:557-561`) — a Go-level API we cannot reach from Kotlin.

### The write path is the real lever — and we can't pull it with `shutdown()`

This is the important discovery:

```go
// connection.go:595-599
go func() {
	if err := c.sendQueue.Run(); err != nil {
		c.destroyImpl(err)                  // ← immediate connection death
	}
}()
```
```go
// send_queue.go:91-99
if err := h.conn.Write(e.buf.Data, e.gsoSize, e.ecn); err != nil {
	var tooLarge *DatagramTooLargeError
	if !isSendMsgSizeErr(err) && !errors.As(err, &tooLarge) {
		return err                          // ANY other write error is fatal
	}
}
```
`destroyImpl` → `setCloseError` → `run()` returns → `defer func() { c.ctxCancel(err) }()` (`connection.go:572`) → `c.conn.Context()` is Done → `hysteria/dialer.go:129-139` returns `StatusInactive` → `dial()` at `:149-156` closes and rebuilds. **Exactly the self-heal we want, with sub-millisecond latency.**

But `shutdown(SHUT_WR)` on a UDP socket does **not** reliably produce a write error on Linux — `udp_sendmsg` has no `sk_shutdown` check (INFERRED, needs empirical confirmation). So the one path that would work is the one `shutdown()` cannot reach.

### Panics

No panic risk from `shutdown()` itself. Existing latent panics that are *not* triggered by this: `basicConn.WritePacket` (`sys_conn.go:113-118`) and `sendQueue.Send` (`send_queue.go:62`). Both are guarded on paths we don't touch.

---

## 3. `shutdown` vs `close` (VERIFIED reasoning)

Your framing is right about fd-number reuse, but both lose:

| | `shutdown(fd)` | `close(fd)` |
|---|---|---|
| fd number | stays owned by Go ✅ | freed → Go may hand it to another socket → subsequent QUIC writes go into a **stranger's socket**, and Go's eventual `pktConn.Close()` (`hysteria/dialer.go:143`) closes a stranger's fd ❌ |
| netpoller | tolerates it, but reports permanent `EPOLLIN` → busy loop ❌ | epoll auto-drops the epitem when the last fd ref goes; the reader **parks forever**, no error, connection never dies ❌ |
| tears down QUIC | no | no |

So `shutdown` is the *safer* variant, and it is still useless. Confirmed as stated in the brief, with the extra finding that safety is not the binding constraint.

**The only fd trick that would deterministically work is `dup2(<a non-socket fd>, targetFd)`** → `sendmsg` returns `ENOTSOCK` → `send_queue.go:98` returns it → `destroyImpl` → ctx cancelled. It keeps the fd number allocated and leaks nothing. I am mentioning it for completeness and **recommending against it**: it still carries an irreducible TOCTOU — if Go closes the netFD microseconds before your `dup2`, the number can be recycled and you silently clobber an unrelated fd. "Reliability is priority #1" rules this out when a clean alternative exists.

---

## 4. Does the pool entry get replaced or leaked? (VERIFIED — no leak)

```go
// hysteria/dialer.go:449-465
manger.mutex.Lock()
c, ok := manger.m[addr]
if !ok { c = &client{...}; manger.m[addr] = c }
c.setCtx(ctx)
manger.mutex.Unlock()
```

The map stores a **pointer to a mutable `client`**. The next `Dial` finds `ok == true`, reuses the same `*client`, and `c.tcp()`/`c.udp()` call `c.dial()`, which at `:149-156` sees `StatusInactive`, calls `c.close()` (`:141-147` — `CloseWithError`, `pktConn.Close()`, nils out `conn`/`pktConn`/`udpSM`), and then dials a fresh QUIC connection, reassigning `c.conn`/`c.pktConn` at `:318-319`.

**It overwrites in place. Nothing leaks, no stale entry survives.** In addition, `init()` at `:477-483` runs `manger.clean()` every 30 s, which closes any inactive client's socket even if nobody dials again (`:428-435` → `:332-339`).

The corollary matters: **the pool was never the bug.** The pool self-heals perfectly. The only thing standing between a dead network and a fresh tunnel is *how long it takes `c.conn.Context()` to be cancelled* — which is the 30 s `MaxIdleTimeout` and nothing else.

---

## 5. What state is stale after a self-heal re-dial? (VERIFIED — all of it)

Captured once, at insert (`hysteria/dialer.go:453-461`): `config` (incl. `Auth`), `tlsConfig`, `socketConfig`, `udpmaskManager`, `quicParams`. On the reuse path (`ok == true`) **only `c.setCtx(ctx)` is refreshed (`:464`)**. `dial()` reads `c.quicParams` (`:158`), `c.socketConfig` (`:172`), `c.tlsConfig` (`:254`), `c.config.Auth` (`:273`) — all the stale ones.

**Definitive answer to the underlying question: an in-process re-dial can NEVER pick up a new config for the same `dest.NetAddr()` key. Not after `StopXray`, not after `RunXrayFromJSON` with new credentials.** Server change, auth change, TLS change, `quicParams` change — all silently ignored as long as the address:port string is unchanged. This is a correctness trap independent of reconnect.

---

## 6. The no-fork levers that DO work

### Lever A — `maxIdleTimeout` + `keepAlivePeriod` (VERIFIED, config-only, zero code) ★ primary recommendation

The 30 s is a *default*, not a hard-coded constant:

```go
// hysteria/dialer.go:226-227, 245-250
MaxIdleTimeout: time.Duration(quicParams.MaxIdleTimeout) * time.Second,
KeepAlivePeriod: time.Duration(quicParams.KeepAlivePeriod) * time.Second,
...
if quicParams.MaxIdleTimeout == 0 { quicConfig.MaxIdleTimeout = 30 * time.Second }
// if quicParams.KeepAlivePeriod == 0 { ... }   ← commented out, hence keepalive disabled
```

Both are reachable from JSON — `infra/conf/transport_internet.go:1720-1724` (`streamSettings.finalmask.quicParams`), fields at `:640-641`, built at `:1979-1980`. Validation at `:1951-1955`: **`maxIdleTimeout` ∈ [4,120]`, `keepAlivePeriod` ∈ [2,60]`.**

```jsonc
"streamSettings": {
  "finalmask": { "quicParams": { "maxIdleTimeout": 6, "keepAlivePeriod": 3 } }
}
```

Effect: on Doze exit or a network flip, the connection is declared dead within ~6 s of CPU-awake time (`connection.go:702-705` → `destroyImpl(qerr.ErrIdleTimeout)` → `:572` `ctxCancel`) and the very next `dial()` rebuilds it in place. **30 s → ~6 s, no fork, no process kill, no fd games, in-process, and it fixes NAT rebinding and silent-blackhole paths too.**

Battery: during suspend the CPU is frozen so no PINGs are emitted at all (`internal/monotime/time.go` — `time.Since(start)`, i.e. `CLOCK_MONOTONIC`, as the analysis already established). Cost is one ~30-byte packet per 3 s while awake — negligible.

**⚠️ Adversarial caveat you must not skip.** `StopXray` (`.libxray-build/libXray/xray/xray.go:106-115`) never touches the pool. Today a stranded client dies of its own idle timeout in 30 s and is reaped by the 30 s `Periodic`. **With `keepAlivePeriod` set, a stranded client PINGs forever and is never reaped — a zombie tunnel on a protected (tun-bypassing) socket, draining battery and holding a server session, permanently.** So Lever A is only safe if *every* stop/config-change path kills the process. That happens to be exactly the owner's chosen design, so the two decisions are coherent — but adopting the keepalive without the process kill would be a regression.

### Lever B — pool-key rotation (VERIFIED mechanics; use only when you must change config in-process)

The earlier rejection assumed a bare IP literal. Two findings reopen it:

1. `DomainAddress` stores the string **verbatim** — no lowercasing, no normalization (`common/net/address.go:79-96`, `:129-131`), and `NetAddr() = Address.String() + ":" + Port` (`common/net/destination.go:90-98`). So `Vpn.example.com:443` and `vpn.example.com:443` are **different pool keys for the same endpoint**, and DNS is case-insensitive. (IPv4-mapped IPv6 does **not** work — `IPAddress` collapses `::ffff:a.b.c.d` to IPv4 at `address.go:107-109`.)
2. **This works even for a bare-IP server**, via `dns.hosts` aliases: `DialSystem` resolves the domain with xray's *own* DNS client when `sockopt.domainStrategy` is set — `transport/internet/dialer.go:252-269` → `LookupForIP` → `dnsClient.LookupIP`, which honours `dns.hosts`. Crucially that resolution happens **inside `DialSystem`, i.e. after** hysteria computed the key at `hysteria/dialer.go:446`. So `alias1.otc:443`, `alias2.otc:443`, … all mint distinct keys and all resolve to the same IP with zero DNS traffic.

SNI is safe: `hysteria/dialer.go:457` calls `tlsConfig.GetTLSConfig()` with **no** destination option, so `ServerName` comes solely from `tlsSettings.serverName` (`transport/internet/tls/config.go:415-417`) and is unaffected by address rotation. Set it explicitly.

Use a **fixed ring of 2–4 aliases**, never a monotonic counter: abandoned entries are reaped by the 30 s `Periodic` but the map key itself is never deleted (`hysteria/dialer.go:428-435` has no `delete`), so unbounded rotation is an unbounded map. And see the zombie caveat above — rotation + keepalive together strand a live tunnel.

### Lever C — process restart (the owner's decision)

Still the only mechanism that is *unconditionally* correct for a config change, and the only one immune to §5's stale-capture trap. Keep it — but with Lever A in place it becomes the rare path (config change, hard stop), not the every-network-flip path.

---

## Ranking

1. **Lever A** — `maxIdleTimeout: 6`, `keepAlivePeriod: 3`. One JSON edit. Turns the 30 s stall into ~6 s with the *existing, verified-correct* self-heal at `hysteria/dialer.go:149-156`. Ship this first and measure; it may be the whole fix.
2. **Lever C** — process restart for config changes and hard stops. Mandatory alongside A, to avoid the zombie-tunnel regression.
3. **Lever B** — alias rotation, only if you later need an in-process config swap without a restart.
4. `dup2` of a non-socket over the fd — works, but carries an irreducible fd-reuse TOCTOU. Do not ship.
5. `shutdown(fd)` — spins a core, gains nothing. **Reject.**
6. `close(fd)` — hangs the reader *and* risks fd-number use-after-close. **Reject.**

**Before shipping A, confirm two things on-device:** that `maxIdleTimeout`/`keepAlivePeriod` survive your config generator into `streamSettings.finalmask.quicParams` (validation will hard-fail the config if out of range, which is a useful smoke test), and that post-Doze recovery now logs a re-dial within ~6 s rather than ~30 s.

---

### A.2 — Android report (restart engine)

> **Adjudicator's note:** §0, §2, §3, §4, §6, §7 and §10 of this report are adopted essentially wholesale and form the basis of the "Restart tier" section. **§1 is rejected** — its connected-socket premise rests on misreading `PacketConnWrapper.RemoteAddr()` (`system_dialer.go:165-167`) as `*net.UDPConn.RemoteAddr()`. Its `protectFd`-counter proposal is adopted as Change 1 and is the most valuable single idea in the report.

# Restart engine for `:vpn` — no-fork design

**Method.** Every AOSP claim below was read from `aosp-mirror/platform_frameworks_base` at `android15-release` and re-checked on `android16-release`; xray/quic-go claims from `/Users/onthecrow/go/pkg/mod/github.com/xtls/xray-core@v1.260327.0`. **VERIFIED** = read in source. **INFERRED** = reasoned, needs a field test.

---

## 0. Headline: three of D10's four "stacked coin flips" are factually wrong

The prior analysis rejected the alarm path on four probabilities. Three do not exist.

| D10 claim | Verdict | Source |
|---|---|---|
| (i) "`setExactAndAllowWhileIdle` rate-limited to ~1 per 9-10 min in deep Doze" | **FALSE.** Stale by four releases. | `AlarmManagerService.java:762` — `DEFAULT_ALLOW_WHILE_IDLE_QUOTA = 72` per `DEFAULT_ALLOW_WHILE_IDLE_WINDOW = 60*60*1000` (`:764`). 72/hour, not 1/9min. Compat quota (pre-S targets) is 7/hour (`:761`). Identical on `android16-release:752-755`. **And for us the quota does not apply at all** — see §2. |
| (iii) "the temporary-allowlist FGS exemption is not on the public list and is user-declinable" | **Irrelevant — we don't need it.** A consented `VpnService` app is *unconditionally* exempt from the FGS background-start restriction. | `ActiveServices.java:8884-8892`: `if (appOpsManager.checkOpNoThrow(OP_ACTIVATE_VPN, callingUid, callingPackage) == MODE_ALLOWED) { ret = REASON_OP_ACTIVATE_VPN; }`. `Vpn.java:1349-1365`: consenting to a `TYPE_VPN_SERVICE` VPN sets `OPSTR_ACTIVATE_VPN` to `MODE_ALLOWED`. Present in `android12/13/14/16-release` (grepped all four). **This exemption is absent from the public docs page.** |
| (iv) "HyperOS Autostart gates manifest-receiver cold starts" | **True, but avoidable** — the design below uses no receiver and no cold *app* start. | §4 |
| (ii) "reintroduces 22.2 s of unprotected traffic" | **True but mis-attributed.** The 22.2 s is `START_STICKY`/AMS backoff, not the restart cost. An alarm-driven restart is ~2-3 s. | §8 |

Consequence: **the alarm-driven restart is far stronger than D10 concluded** — it is one high-probability mechanism, not four low ones multiplied.

---

## 1. Evaluate first: the `protectFd` + `shutdown(fd)` proposal

This can make the restart unnecessary. It is **viable and worth building first**, with one specific identified failure mode.

**VERIFIED — the socket is `connect()`ed.** `dialer.go:172` calls `internet.DialSystem(c.ctx, c.dest, c.socketConfig)`; the switch at `:180-186` does `remote = conn.RemoteAddr().(*net.UDPAddr)`. `RemoteAddr()` returns `nil` on an unconnected `*net.UDPConn` and that assertion would panic. So it is a connected UDP socket → `sk_state == TCP_ESTABLISHED` → `shutdown(2)` returns success rather than `ENOTCONN`. **This is the precondition the whole idea rests on, and it holds.**

**VERIFIED — the hook exists.** `PlatformXrayEngine.android.kt:184-185` registers the proxy for `registerDialerController`/`registerListenerController`; `ProtectFdInvocationHandler.invoke` (`:221-240`) receives every fd before connect.

**Implementation note — do NOT use reflection on `FileDescriptor`.** `Os.shutdown` needs a `FileDescriptor`, and `FileDescriptor.setInt$` is hidden/blocklisted. Use `ParcelFileDescriptor.fromFd(fd)` — public API, and it **dups**. `shutdown()` acts on the underlying socket (the file *description*), so shutting down the dup tears down Go's socket, and closing the dup is safe. This sidesteps the fd-aliasing hazard entirely.

```kotlin
ParcelFileDescriptor.fromFd(recordedFd).use { pfd ->
    Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR)
}
```

**ADVERSARIAL — where it fails.** Go sets `ZeroReadIsEOF = (sotype != SOCK_DGRAM && sotype != SOCK_RAW)` (`internal/poll/fd_unix.go`). For UDP it is **false**, so after `SHUT_RD` `recvmsg` returns 0 and Go returns `(0, nil)` — **not an error**. quic-go's read loop drops the empty packet and loops → a **CPU busy-spin, and no connection teardown**. Teardown therefore depends entirely on the *write* side: after `SHUT_WR`, `sendmsg` returns `EPIPE`, which quic-go treats as fatal and destroys the connection, cancelling `c.conn.Context()` → `status()` → `StatusInactive` → `dial()` re-dials fresh (`dialer.go:149-156`). **INFERRED, must be measured.**

**Therefore the ordering is the opposite of the fork's.** The fork calls `CloseAll()` *after* `StopXray`. Here you must shutdown **while xray is still running and carrying traffic**, so a write is actually attempted:

```
shutdown(SHUT_RDWR) on recorded QUIC fds   ← xray still live; next outbound write → EPIPE → conn destroyed
  → wait for teardown (poll, ~50-200 ms)
  → StopXray → setTunFd(freshDup) → start   ← dial() now sees StatusInactive → fresh client
```

If you `StopXray` first, no handler writes again, `EPIPE` never fires, `status()` still reports Active, and **the pool hands back the corpse** — the exact bug you were trying to fix, now with a spinning read loop attached.

**Free verifier, ship this regardless.** `protectFd` is called *only when a new socket is created*. So: log every `protectFd` with a monotonic counter, and log every recovery. **A recovery that produces no `protectFd` call did not evict the pool.** This is a zero-cost, unambiguous oracle for whether *any* eviction technique worked — better than the DNS probe, and it should have existed before any of this was attempted.

**Ranking:** try this before building the restart engine. If the `protectFd` counter increments on every recovery across a 200-cycle stress run with no CPU spin, you never need §2-§7. Budget one day. If it fails, the restart engine below is the answer.

---

## 2. The quota and permission questions — verified, and the answer is counter-intuitive

Our app: `targetSdk 36`, requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`AndroidManifest.xml:9`), does **not** declare `SCHEDULE_EXACT_ALARM`.

**(a) Does `canScheduleExactAlarms()` return true for a Doze-allowlisted app without `SCHEDULE_EXACT_ALARM`? — YES. VERIFIED.**

`AlarmManagerService.java:2869-2871`:
```java
return isExemptFromExactAlarmPermissionNoLock(packageUid)
    || hasScheduleExactAlarmInternal(...) || hasUseExactAlarmInternal(...);
```
and `:2689-2695`:
```java
boolean isExemptFromExactAlarmPermissionNoLock(int uid) {
    return (UserHandle.isSameApp(mSystemUiUid, uid) || UserHandle.isCore(uid)
        || mLocalDeviceIdleController == null
        || mLocalDeviceIdleController.isAppOnWhitelist(UserHandle.getAppId(uid)));
}
```
`isAppOnWhitelist` → `DeviceIdleController.java:2604-2607` → `mPowerSaveWhitelistAllAppIdArray`, which includes user-added apps. **The battery-optimization allowlist alone grants exact alarms.** `setExactAndAllowWhileIdle` also does not throw — `:2819` yields `EXACT_ALLOW_REASON_ALLOW_LIST` instead of the `SecurityException` at `:2823-2829`.

**(b) Is an allowlisted app exempt from the quota? — YES, completely. VERIFIED, and this contradicts Google's own Doze doc**, which still states "the once-per-9-minutes rule still applies to allowlisted apps."

`AlarmManagerService.java:2753-2761`:
```java
} else if (workSource == null && (UserHandle.isCore(callingUid)
        || UserHandle.isSameApp(callingUid, mSystemUiUid)
        || ((mAppStateTracker != null)
        && mAppStateTracker.isUidPowerSaveUserExempt(callingUid)))) {
    flags |= FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
    flags &= ~(FLAG_ALLOW_WHILE_IDLE | FLAG_PRIORITIZE);
}
```
and `:2443-2445` (device-idle policy) / `:2381-2383` (battery-saver policy):
```java
if ((alarm.flags & (FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED | FLAG_WAKE_FROM_IDLE)) != 0) {
    deviceIdlePolicyTime = nowElapsed;   // Unrestricted.
}
```
The quota branch is never reached. **Pass `workSource == null`** (i.e. plain `setExactAndAllowWhileIdle`, no `setWithWorkSource` variant) or you lose this.

**(c) The trap nobody would predict.** In the allowlist-but-no-permission branch, `:2837` *overwrites* the broadcast options:
```java
idleOptions = allowWhileIdle ? mOptsWithoutFgs.toBundle() : null;
```
Because `FLAG_ALLOW_WHILE_IDLE` was already cleared at `:2760`, `allowWhileIdle` is `false` → **`idleOptions = null` → no temporary power allowlist, no FGS capability from the alarm at all.** Holding `SCHEDULE_EXACT_ALARM` instead would have kept `mOptsWithFgs` (`:2796-2797`, configured at `:890-892` with `TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED`, 10 s per `:738`).

**This does not hurt us** — `REASON_OP_ACTIVATE_VPN` (§0) grants the FGS start independently, as does `REASON_SYSTEM_ALLOW_LISTED` (`ActivityManagerService.java:6497-6503` → `mDeviceIdleExceptIdleAllowlist` → `FAKE_TEMP_ALLOW_LIST_ITEM` → `ActiveServices.java:8804-8807`). But it means **you must not reason "the alarm grants the FGS start."** It doesn't, in our configuration. Three independent grants exist; the alarm is not one of them.

**(d) Method choice.**
- `setExactAndAllowWhileIdle` — **use this.** Unrestricted for us, no quota, no permission needed.
- `setAndAllowWhileIdle` — inexact; gets `mOptsWithoutFgs` (`TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED`, `:897-898`). Fine as the `SecurityException` fallback.
- `setAlarmClock` — **reject.** `FLAG_WAKE_FROM_IDLE`, unrestricted, wakes the device early — but it renders a **user-visible alarm icon in the status bar** and appears in Clock. Unacceptable for a VPN reconnect, and Play-policy adjacent.

---

## 3. `SCHEDULE_EXACT_ALARM` vs `USE_EXACT_ALARM` — declare neither as primary

- **`USE_EXACT_ALARM`**: auto-granted, non-revocable, but Play-policy restricted to alarm-clock/calendar/timer apps. A VPN declaring it is a plausible rejection. **Reject.**
- **`SCHEDULE_EXACT_ALARM`**: user-revocable on 13+; **denied by default** for apps targeting 14+ (`AlarmManagerService.java:2662`, `isScheduleExactAlarmDeniedByDefault`). On revocation the system sends `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` and removes pending exact alarms (`:4795-4796`); a subsequent `setExactAndAllowWhileIdle` throws `SecurityException` — **unless** `isExemptFromExactAlarmPermissionNoLock` saves you, which for us it does.
- **Recommendation: declare `SCHEDULE_EXACT_ALARM` as a belt-and-braces fallback only**, and make the design correct without it. Rationale: it costs a Play declaration form (defensible for a VPN) and it covers the case where the user later revokes battery-optimization exemption. But **always** wrap the call:

```kotlin
try { am.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
catch (e: SecurityException) { am.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
```

Use `ELAPSED_REALTIME_WAKEUP`, never `RTC_*` — `RTC` alarms move when the clock is corrected by NTP after a Doze exit.

---

## 4. Manifest receiver as the entry point — reject

A manifest receiver works (manifest receivers are startable by an explicit `PendingIntent.getBroadcast` and are not subject to the implicit-broadcast ban), but it buys nothing and costs three things: an extra process/component start hop, ~10 s `onReceive` budget pressure, and — because the receiver would live in the *default* process by default — a **main-process cold start**, exactly the dependency step 4 of the analysis deleted.

`PendingIntent.getForegroundService()` with an explicit `ComponentName` for `DeltaVpnService` lands **directly in `:vpn`**, no receiver, no main process, no `START_STICKY`. Use that.

**HyperOS/MIUI Autostart:** gates cold starts of the app's components generally — receivers *and* service starts. It is not receiver-specific, so avoiding the receiver does not avoid Autostart. This is the one candidate-independent residual, addressed only in §6.

---

## 5. JobScheduler / WorkManager — reject as primary, keep one job as a backstop

**VERIFIED from the Doze doc:** Doze "doesn't let `JobScheduler` run (and by extension `WorkManager` tasks don't run)". **Expedited work does not change this** — expedited jobs bypass App Standby bucket quotas, not Doze; they are deferred to the next maintenance window. Maintenance windows stretch to hours in deep Doze. Unusable as the restart trigger.

WorkManager additionally requires initialization in the process that enqueues — pulling `androidx.work` into `:vpn` or routing through the main process. Both are regressions.

**Keep exactly one thing:** a `JobInfo` with `setRequiredNetworkType(NETWORK_TYPE_ANY)` + `setPersisted(true)`, scheduled once, as a *last-ditch* self-heal for the case where the alarm was lost (reboot race, OEM alarm purge). It fires whenever the device next wakes. Treat it as "eventually", not "reliably".

---

## 6. Always-on VPN + lockdown — the only mechanism that beats the OEM

**What it guarantees:** the system, not the app, owns the lifecycle — it starts the VPN service at boot and re-invokes it when it dies, from `system_server`, which no OEM Autostart manager gates. **Lockdown additionally installs UID-range blackhole rules, so the restart gap fails *closed*** — this is the only thing in this document that reduces the leak window (§8) to zero.

**Detection: there is no public API. VERIFIED by absence** — `VpnManager` and `ConnectivityManager` expose no always-on getter to third-party apps. The `Settings.Secure` key `always_on_vpn_app` is `@hide` and per-user; whether a third-party read succeeds on Android 16 is **UNVERIFIED**. Test before shipping any UI that depends on it:

```
adb shell settings get secure always_on_vpn_app
adb shell settings get secure always_on_vpn_lockdown
```
and from the app, `Settings.Secure.getString(cr, "always_on_vpn_app")` — expect `null` (blocked) as the likely outcome. **Design the UI so a `null` read means "unknown", never "not enabled"** — nagging a user who already enabled it is the failure mode here.

**Guidance:** deep-link `Settings.ACTION_VPN_SETTINGS`, shown once after the first successful connection, dismissible, never modal.

---

## 7. Ranked design

| Rank | Mechanism | Reliability | Why |
|---|---|---|---|
| **R0** | `shutdown(fd)` in-process eviction (§1) | **Unknown — 0 % or ~99 %.** Binary, cheap to determine. | If the `EPIPE` teardown fires, no restart is ever needed. Zero leak. Test first. |
| **R1** | `setExactAndAllowWhileIdle` → `PendingIntent.getForegroundService(DeltaVpnService)` | **~97 % stock AOSP; ~85-90 % HyperOS** | No quota (§2b), no permission (§2a), FGS start triple-granted (§0). Sole residuals: OEM Autostart, LMK during the gap, alarm loss across reboot. |
| **R2** | Always-on VPN + lockdown (user-configured) | **~99.9 %, but not app-controllable** | Only defence against OEM kills; only zero-leak restart. Cannot be granted programmatically. |
| **R3** | Persisted `JobScheduler` job, network-triggered | **"eventually"** | Catches lost alarms. Deferred in Doze. |
| **R4** | `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` | **N/A — different failure** | Not a restart engine; restores after reboot/update. |
| — | `START_STICKY`, manifest receiver, WorkManager expedited, `setAlarmClock` | **Rejected** | §4, §5, §2d, D9 |

**`BOOT_COMPLETED` is safe for us — VERIFIED.** Android 14+ restricts FGS types startable from `BOOT_COMPLETED` (`ActiveServices.java:1179-1182`, change id `FGS_BOOT_COMPLETED_RESTRICTIONS = 296558535L`), but `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` is in the default allowlist (`ActivityManagerConstants.java:200-206`). And because we are battery-allowlisted, `isAllowlistedForFgsStartLOSP` returns `FAKE_TEMP_ALLOW_LIST_ITEM` → `REASON_SYSTEM_ALLOW_LISTED`, not `REASON_BOOT_COMPLETED`, so the type check at `:1180` is never even reached.

### Manifest delta

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- Fallback only: the battery-optimization allowlist already grants exact alarms
     (AlarmManagerService#isExemptFromExactAlarmPermissionNoLock). Play declaration required. -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

<receiver android:name=".vpn.BootRestoreReceiver"
          android:exported="false"
          android:process=":vpn"
          android:directBootAware="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```
`android:process=":vpn"` on the receiver is load-bearing — it keeps the main process off the path. `WAKE_LOCK` is **absent today** and is required by step 4 of the analysis regardless.

### Code shape (R1)

```kotlin
// In :vpn, on the T3 path only. Order matters.
private fun restartProcess(reason: String) {
    paramsStore.save(currentParams)                     // 1. persist FIRST
    recoveryPrefs().edit().putInt(KEY_RESTART_GEN, gen + 1).commit()  // commit, not apply

    val intent = Intent(this, DeltaVpnService::class.java)
        .setAction(ACTION_CONNECT)
        .putExtra(EXTRA_RESTART_REASON, reason)
    val pi = PendingIntent.getForegroundService(
        this, REQ_RESTART, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val at = SystemClock.elapsedRealtime() + RESTART_DELAY_MS   // 500
    val am = getSystemService(AlarmManager::class.java)
    try { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
    catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }

    stopSelf()                                          // 2. clears AMS "wants restart" -> no START_STICKY race
    Process.killProcess(Process.myPid())                 // 3.
}
```

Four non-obvious requirements:

1. **`stopSelf()` before the kill.** Without it AMS also schedules a `START_STICKY` restart, which races the alarm for the same component and reintroduces the ×4 `SERVICE_RESTART_DURATION` escalation (D9). The existing comment at `DeltaVpnService.kt:322` shows this is already understood — keep it. Better still, **return `START_NOT_STICKY`** from `onStartCommand` (currently `START_STICKY` at `:151`) once the alarm engine lands; `START_STICKY` is now pure liability.
2. **`FLAG_IMMUTABLE`** — mandatory from Android 12 (`PendingIntent` without a mutability flag throws).
3. **`RESTART_DELAY_MS = 500`.** Non-zero to let the process actually die; if the alarm fires while the old process is still terminating, AMS may deliver `onStartCommand` into the dying process and the restart is silently lost. 500 ms is empirical headroom — **INFERRED, tune under the stress test.**
4. **A persisted restart generation counter**, `commit()`-ed not `apply()`-ed (the process is about to be SIGKILLed). Use it to enforce backoff across process deaths — otherwise every fresh process starts with a clean debounce and can hot-loop. The existing `recordRecoveryKill` (`:418-425`) already uses `commit()` and `elapsedRealtime()`; note `elapsedRealtime()` **survives** the process death but **resets at reboot**, which is correct behaviour here.

---

## 8. Timing and leak window

Screen-on, per candidate, from "decide to restart" to "fresh xray has dialled":

| Phase | Cost | Basis |
|---|---|---|
| persist + schedule + kill | ~20-40 ms | `commit()` + binder |
| alarm delay | 500 ms | chosen (§7.3) |
| AMS process fork + `Application.onCreate` | ~150-300 ms | INFERRED |
| `System.loadLibrary` of libXray (large Go .so, relocations) | ~200-500 ms | **INFERRED — measure this; it is the largest unknown and the main argument against restarting at all** |
| `Builder.establish()` + netd rule install | ~150-350 ms | INFERRED |
| `setTunFd` + `runXrayFromJSON` + dial | ~190-272 ms | **measured** in the analysis |

**Screen-on total: ~1.2-2.0 s. Doze: the same** — the alarm is `FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED` so there is no deferral (§2b), and network access is available because we are Doze-allowlisted. Add cellular re-attach if the radio was down: **+1-4 s, unbounded on a bad link.**

Compare: **R0 (§1) is ~250-450 ms and needs none of the above.** That gap — 4-8× — is the real argument for spending a day on the `shutdown(fd)` experiment.

**Leak window.** Today's 22.2 s is `START_STICKY` backoff, not intrinsic. With R1 the tun dies at `killProcess` and is reborn at `establish()` → **~1.0-1.6 s of clear traffic** per restart. Minimisation, in order of effectiveness:

1. **Always-on + lockdown (R2)** — the *only* true fix. Lockdown blackholes the UID range while no VPN is up, so the gap fails closed. Everything else is mitigation.
2. **Never take this path.** Fix R0 or the fork; make T3 fire ~never. The leak is per-restart, so restart frequency is the dominant term.
3. **Do not shorten `RESTART_DELAY_MS` below 500 ms** to chase it — a lost restart is an *unbounded* leak, strictly worse than 500 ms of bounded leak. This trade is asymmetric; do not optimise the wrong side.
4. Establish the tun **before** starting xray in `onStartCommand` (routes captured and blackholed by the tun while xray dials) — shaves the ~250 ms dial off the leak, at the cost of ~250 ms of blackholed rather than clear traffic. Correct trade for a VPN.

---

## 9. Can the Go runtime be reset in-process? — No.

**Verified, and the reasons are structural, not incidental:**

1. **No exported hook.** `grep "^func [A-Z]"` across `.libxray-build/libXray/*.go` yields `InitDns`, `ResetDns`, `RegisterDialerController`, `RegisterListenerController`, `SetTunFd`, `RunXray`, `RunXrayFromJSON`, `StopXray`, `GetXrayState`, `TestXray`, `Ping`, `QueryStats`, geo/share helpers. Nothing touches `manger`. Confirms D14.
2. **The Go runtime has no teardown API.** There is no `runtime.Reset()`. Package-level `var`s are initialised once by the `init` chain at library load; the language provides no re-run.
3. **`dlclose` will not work.** The Go runtime installs process-wide signal handlers (`SIGSEGV`, `SIGURG`, `SIGPROF`), creates TLS keys and M/G scheduler state, and starts non-joinable threads (sysmon). It never unregisters any of it, so `dlclose` leaves dangling handlers pointing into unmapped memory → immediate crash on the next signal. Go explicitly documents `-buildmode=c-shared` libraries as non-unloadable.
4. **Loading a second copy under a different soname** would put two Go runtimes in one process contending for the same signal handlers and TLS slots. Unsupported; gomobile has no shared-runtime mode for this.
5. **Isolating xray in a third process** would work — but it is strictly worse than restarting `:vpn`: you would then need to pass the tun fd across a binder boundary *and* keep two processes alive, doubling the LMK surface for zero gain over R1.

**Therefore: process death is the only in-app way to clear Go package state.** The only alternatives are (a) make the pool self-evict via `status()` — R0/§1, or the fork — or (b) don't need to evict it.

---

## 10. What the user must configure manually

Ranked by value; all are un-grantable programmatically.

1. **Always-on VPN + lockdown** (`Settings.ACTION_VPN_SETTINGS`). The only defence against an OEM killing `:vpn`, and the only way to make the restart gap fail closed. Frame as "Block connections without VPN".
2. **Battery optimization off** (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — permission already declared, `AndroidManifest.xml:9`). **This is now known to be load-bearing for three separate things:** exact-alarm eligibility without `SCHEDULE_EXACT_ALARM` (§2a), quota exemption (§2b), and an FGS-start grant (§0). Verify at runtime with `PowerManager.isIgnoringBatteryOptimizations` and re-prompt if it is ever revoked — and **log the value on every recovery**, because a silent revocation changes the failure mode entirely.
3. **HyperOS/MIUI Autostart** — enable for the app. Not detectable; show as a checklist item on Xiaomi builds only (`Build.MANUFACTURER`).
4. **HyperOS battery saver: "No restrictions"** — distinct from #2 on Xiaomi and separately capable of killing `:vpn`.

---

## 11. Honest bottom line

- **The alarm restart engine is materially better than D10 concluded** — no quota, no permission required, FGS start unconditionally legal for a consented VPN app via an exemption absent from the public docs. Call it **~97 % on stock, ~85-90 % on HyperOS**, at ~1.2-2.0 s and ~1.0-1.6 s of clear traffic per firing.
- **It is still 4-8× slower and strictly leakier than any in-process fix**, so it should be tier 4 in exactly the shape the analysis proposed — just with far more confidence that it will actually fire when it is needed.
- **Do §1 first.** It is a one-day experiment with a binary outcome, it uses a hook you already own, the connected-socket precondition is source-verified, and it makes §7 nearly vestigial. Its identified failure mode (Go's `ZeroReadIsEOF == false` for UDP → no read error → teardown depends on a write hitting `EPIPE`) is specific enough to test directly, and the `protectFd` counter gives you an unambiguous pass/fail oracle.
- **The one thing to stop believing:** that the alarm confers the FGS start. In this app's configuration it provably does not (`AlarmManagerService.java:2837`). The grant comes from `OP_ACTIVATE_VPN` and from the battery allowlist. If a future change drops the battery-optimization exemption, the alarm still fires and the FGS start still succeeds — but for a reason nobody wrote down. Write it down.

**Sources:** [FGS background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms), [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby), [Exact alarms denied by default (14)](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms), [Android 13 alarm restrictions analysis](https://www.esper.io/blog/android-13-exact-alarm-api-restrictions). AOSP source read from `aosp-mirror/platform_frameworks_base` branches `android12/13/14/15/16-release`.

---

### A.3 — Systems report (fd mechanics & leak)

> **Adjudicator's note:** §1 is adopted as the decisive verdict, and its `dup()`-inside-the-callback insight is retained as a diagnostic technique. §2.1's "the 22.2 s is ~97 % scheduling delay" framing is adopted. §3.2's `:xray` process split is **deferred, not rejected** — it is the correct next move if field data shows T2/T3 firing often. §4's two corrections to the analysis document are confirmed against the tree.

# Socket/fd-level truth behind the two candidate designs

**Verdict up front.** The fd-shutdown idea **cannot be made to work** — not because of the identity race (that one *is* solvable, cleanly, with `dup()`), but because the socket xray hands us is an **unconnected** UDP socket and Linux's UDP stack ignores `sk_shutdown` on both the send and the non-blocking receive path. The idea fails on effect, not on safety. Section 1 proves this.

That result forces the restart path — but the restart does **not** have to be a restart of `:vpn`. Section 3 gives a third-process design (`:xray`) that delivers exactly the semantics the owner asked for ("just restart the process") with a **zero-byte leak window**, no fork, and ~300 ms recovery. That is the recommendation.

---

## 1. The fd-shutdown idea

### 1.1 The fd we receive is valid only for the duration of the callback — VERIFIED

The call chain is:

```
libXray/android_wrapper.go:24-28   RegisterDialerController(controller) → c.RegisterDialerController(func(fd uintptr){ controller.ProtectFd(int(fd)) })
libXray/controller/controller_android.go:13-18   xinternet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error { return conn.Control(controller) })
```

`conn.Control` is `syscall.RawConn.Control`, whose contract is explicit in the Go source (`$GOROOT/src/syscall/net.go:9-13`):

> `// The file descriptor fd is guaranteed to remain valid while f executes but not after f returns.`

The enforcement is `internal/poll/fd_posix.go:56-63`:

```go
func (fd *FD) RawControl(f func(uintptr)) error {
	if err := fd.incref(); err != nil { return err }
	defer fd.decref()
	f(uintptr(fd.Sysfd))
	return nil
}
```

`incref`/`decref` is a refcount that only defers `close()` **for the duration of the callback**. The moment `ProtectFdInvocationHandler.invoke` (`PlatformXrayEngine.android.kt:221-240`) returns, Go may close that fd, and the number is immediately available for reuse by any thread in the process — ART, Binder, the tun `dup()`, an okhttp socket, a `.so` mapping.

**So: recording the raw int and acting on it later is a use-after-free on an fd number.** This is the documented-unsafe case, not an edge case.

### 1.2 The identity race — solvable, and the solution is not a check

You asked me to design a positive identity check (`fstat` st_ino, `getsockopt`, `/proc/self/fd` readlink). **All of them are wrong**, and it is worth being precise about why: every one is check-then-act, and there is no atomic "operate on this fd only if it is still inode X" syscall on Linux. Between your `fstat` and your `shutdown` the fd can be closed and recycled. Narrowing the window does not close it, and the failure mode is catastrophic (you `shutdown()` the tun, or a Binder fd, and the symptom is indistinguishable from the bug you are chasing).

The correct technique is to **not need a check**:

```kotlin
// INSIDE the protectFd callback, where the fd is guaranteed valid:
val myFd: FileDescriptor = Os.dup(fdFromGo)   // our own fd number, our own ownership
```

`dup(2)` creates a second fd referring to the **same open file description** and the same `struct socket`/`struct sock`. Consequences:

- Our number can never be recycled out from under us — we own it until *we* `close()` it.
- Operations that act on the socket (`shutdown`, `setsockopt`, `getsockname`, `connect`) affect the object Go is using, because `SYSCALL_DEFINE2(shutdown, ...)` resolves the fd to `struct socket` and calls `sock->ops->shutdown(sock, how)` — shared state, not per-fd state.
- Operations that act on the *fd* (`close`) do not, because the socket is refcounted.

This is the right primitive and it fully dissolves the race. It just does not buy us anything, because of §1.3.

Cost note if you ever do use it: xray protects **every** outbound socket, so an unfiltered dup-and-retain is an fd leak proportional to connection count. You would have to filter at protect time (`Os.getsockopt(fd, SOL_SOCKET, SO_TYPE) == SOCK_DGRAM` plus `SO_DOMAIN`), and you still cannot identify *which* UDP socket is the hysteria one at protect time — see §1.4.

### 1.3 `shutdown()` on this socket does nothing useful — the fatal objection

**The socket is unconnected.** VERIFIED at `xray-core@v1.260327.0/transport/internet/system_dialer.go:53-87`:

```go
if dest.Network == net.Network_UDP && !hasBindAddr(sockopt) {
    ...
    var lc net.ListenConfig
    lc.Control = func(network, address string, c syscall.RawConn) error { /* our controllers run here */ }
    packetConn, err := lc.ListenPacket(ctx, srcAddr.Network(), srcAddr.String())   // 0.0.0.0:0
    return &PacketConnWrapper{PacketConn: packetConn, Dest: destAddr}, nil
}
```

`ListenPacket` on `0.0.0.0:0`, never `connect()`ed. The destination is carried in userspace (`PacketConnWrapper.Dest`, `:157-159`) and every write is a `sendto()` with an explicit address. `hysteria/dialer.go:177-181` takes exactly this `*internet.PacketConnWrapper` branch and hands `pktConn` to `quic.DialEarly`.

Now the kernel behaviour, direction by direction:

**Receive side.** `inet_shutdown()` (`net/ipv4/af_inet.c`) for `sk->sk_state == TCP_CLOSE` (an unconnected UDP socket) returns `-ENOTCONN` but **falls through** and still sets `sk->sk_shutdown |= how` and calls `sk->sk_state_change(sk)` — the kernel's own comment calls this a "hack to wake up other listeners... even on eg. unconnected UDP sockets". So the side effect happens despite the errno. That gives you:

- One `sk_state_change` wakeup → one epoll edge. Go registers fds **edge-triggered** (`_EPOLLIN|_EPOLLOUT|_EPOLLRDHUP|_EPOLLET` in `runtime/netpoll_epoll.go`), and translates `EPOLLHUP`/`EPOLLRDHUP` to "readable" only — it does **not** synthesise an error; it re-issues the syscall and trusts errno.
- Go's reader then calls `recvmsg`. `__sys_recvfrom` ORs in `MSG_DONTWAIT` because the fd is `O_NONBLOCK`, so `sock_rcvtimeo()` yields `timeo == 0`, so `__skb_recv_udp`'s `while (timeo && !__skb_wait_for_more_packets(...))` never runs. The `RCV_SHUTDOWN → return 0 (EOF)` behaviour lives **inside** `__skb_wait_for_more_packets`, i.e. only on the blocking path. Non-blocking with an empty queue returns **`-EAGAIN`**.
- `EAGAIN` → Go parks on the netpoller again. No further edges are generated. **The goroutine parks forever; no error ever reaches quic-go.**

**Send side.** `udp_prot` has no `.shutdown` method, and `udp_sendmsg()` has no `sk_shutdown & SEND_SHUTDOWN` check (that check is TCP-only, in `tcp_sendmsg_locked`). **Sends continue to succeed.**

**Therefore `shutdown(SHUT_RDWR)` produces: one spurious wakeup, permanent `EAGAIN`, and working writes.** The QUIC connection survives until the 30 s idle timer — exactly the state we were trying to escape.

And connectedness would not rescue it: for `TCP_ESTABLISHED` UDP, `inet_shutdown` hits the `default:` arm, sets `sk_shutdown`, returns 0 — and the non-blocking receive path is identical. The blocking-recv-returns-0 folklore simply does not apply to a netpoller-driven socket.

**What quic-go *would* have done if we could induce a real error** (so the negative result is precisely scoped):

- `transport.go:545-551` — any read error that is not `net.Error.Temporary()` and not `isRecvMsgSizeErr` → `t.close(err)`, tearing down the transport and every connection on it. On Linux `isRecvMsgSizeErr` is hardcoded `false` (`sys_conn_df_linux.go:42`), so *any* hard read error suffices.
- `send_queue.go:91-99` — any write error other than `EMSGSIZE` (`sys_conn_df_linux.go:37-40`) → `Run()` returns the error → connection destroyed.

Either would cancel `c.conn.Context()`, flip `client.status()` to `StatusInactive` (`hysteria/dialer.go:129-139`), and make the very next `dial()` re-dial (`:149-156`). The mechanism is real. We just have no syscall that triggers it on an unconnected UDP socket.

### 1.4 Adversarial sweep of the neighbouring ideas — all rejected

| Idea | Why it fails |
|---|---|
| `close()` the socket | Go owns the fd; we cannot close its number, and closing our `dup` is refcounted away. |
| `dup2(blackholeFd, goFd)` — atomically replace Go's fd with something that returns `ENOTSOCK` | Mechanically it *would* work (hard error → `t.close`). But it is the identity race in its most dangerous form: no atomic compare-and-`dup2` exists, and a miss silently clobbers an unrelated fd (tun, Binder, ART). **Reject unconditionally.** |
| `connect()` our dup to a blackhole so sends fail `EISCONN` | Linux is not BSD: `udp_sendmsg()` accepts `sendto()` with an explicit address on a connected socket. Sends keep working. Inbound stops matching the 4-tuple, so we degrade to silence → the 30 s idle timer again. |
| `SO_BINDTODEVICE` to `lo` on our dup | Requires `CAP_NET_RAW`. An app UID does not have it. |
| `SO_SNDBUF = 0` to force `ENOBUFS` | `ENOBUFS` *is* fatal to `sendQueue.Run` — but `SO_SNDBUF` has a kernel floor and UDP wmem is released on transmit, so it is not reliably reachable. Non-deterministic; unacceptable for a reliability-first design. |
| Inject a forged CONNECTION_CLOSE / stateless reset (including via the tun fd we own) | **This is asking to do to QUIC precisely what QUIC is cryptographically designed to prevent.** The only in-band terminators are an AEAD-authenticated `CONNECTION_CLOSE` under 1-RTT keys, or a stateless reset carrying the peer's 16-byte token. We possess neither. Off-path termination is the threat model. |
| `IP_RECVERR` + induced ICMP | Requires an off-path packet source we do not have, and whether `sk_err` surfaces on the non-blocking `udp_recvmsg` path is kernel-version-dependent. Unverifiable here, unreliable in principle. |

### 1.5 SELinux / seccomp — not the blocker

INFERRED (standard AOSP policy, not read off this device): `shutdown` is in the `socket`-class permission set granted by `allow untrusted_app self:udp_socket { ... shutdown ... }`; the app seccomp filter (`bionic/libc/seccomp`) permits `shutdown(2)`. Same UID, same process, same security context — there is no MAC boundary between a Go-created socket and Kotlin code in the same process. `android.system.Os.shutdown(FileDescriptor, int)` has existed since API 21.

**This does not matter.** The idea dies at §1.3, in the kernel's UDP fast path, before it reaches any policy check.

### 1.6 One thing worth salvaging

The `dup()`-inside-the-callback technique is correct and reusable. If you ever need to *observe* xray's upstream socket — confirm it is actually bound to the physical interface after a handover, read `SO_ERROR`, read `getsockname()`/`SO_MARK` to prove `protect()` took effect — this is how you hold a safe handle. It is a diagnostic instrument, not a lever.

---

## 2. Restart cost, at the syscall level

### 2.1 The 22.2 s is not syscall time

Derived budget for the operations in the current kill-and-rebuild path:

| Stage | Realistic cost | Notes |
|---|---|---|
| `Process.killProcess` → fd teardown | 5-20 ms | Kernel closes the fd table; the tun's last reference drops. |
| system_server notices, tears the VPN down | 10-50 ms | `Vpn` death recipient → `agentDisconnect()` → netd removes the interface, UID ranges, and routing rules. |
| **Gap: nothing scheduled** | **seconds to tens of seconds** | AMS `SERVICE_RESTART_DURATION` with ×4 escalation, or exact-alarm quota in Doze, or OEM autostart gating. |
| Zygote fork + `:vpn` process start | 80-250 ms | Fork is cheap; class loading + `libXray.so` dlopen dominate. |
| `Builder.establish()` | 30-120 ms | Binder to system_server → `Vpn.establish()` → tun create + `NetworkAgent` register + netd rule install. |
| netd per-UID rule install (async, **after** `establish()` returns) | 20-200 ms | This is the window that makes the current probe lie (`DeltaVpnService.kt:222-224` already documents it). |
| xray start + fresh hysteria dial | 190-272 ms | Measured in the analysis. |

**Sum of real work: well under 1 second.** The measured 22.2 s is therefore ~97 % *scheduling and policy delay*, not I/O. That is the actionable conclusion: optimising `establish()` or fd teardown is pointless. The only thing worth attacking is the "who restarts us, and when" gap — and the only way to win that is **to not need anyone to restart us**.

### 2.2 What can be avoided or overlapped

- **`Builder.establish()`, `NetworkAgent` registration, netd rule install: avoidable entirely** — by never closing `tunInterface`. Note the working tree already has the *comment* for this at `DeltaVpnService.kt:284-288` ("we dup so xray can be stopped/restarted... without ever closing the master `tunInterface`") while `runConnect` unconditionally calls `stopTunnel()` at `:213`. D5 in the analysis is confirmed: the comment describes a design that is not in the tree.
- **Process spawn: overlappable** — you can start and warm a replacement process *while the old one is still serving*. See §3.
- **AMS restart backoff: avoidable** — only if the thing being restarted is a **bound** service supervised by a live process, not a service AMS is restarting on its own schedule.

---

## 3. Closing the leak — what Android actually permits

### 3.1 What does not work

- **Keep the old tun fd alive across the restart.** You can pass the `ParcelFileDescriptor` over Binder to another process before dying, and the *file* will stay open. It buys nothing: system_server tears the VPN down when the `VpnService`'s process dies (the `Vpn` object's death recipient), and the netd per-UID rules that steer app traffic into the interface go with it. An open fd to an interface no traffic is routed to is a leak, not a tunnel.
- **A blackhole route.** An app cannot install routing rules or `ip rule` entries. There is no API. The only app-visible routing surface is `VpnService.Builder`, and it only works while a VPN session exists.
- **A second process holding "the interface."** Only the process hosting the `VpnService` component matters to `ConnectivityService`. There is no handover primitive.
- **Always-on VPN + lockdown** is the only true system-owned fail-closed, and it is a user setting with no programmatic grant and no public read API. `DevicePolicyManager.setAlwaysOnVpnPackage(admin, pkg, /*lockdown=*/true)` requires Device Owner / Profile Owner — not available to a consumer app. Recommend it in-app; do not count it.

### 3.2 What does work: split xray into its own process

**The recovery unit that must be destroyed is the Go runtime, not the VpnService.** Everything the leak costs — the tun, the netd rules, the `NetworkAgent`, `establish()` latency — belongs to the `VpnService`. Everything we need to destroy — `manger`, the pooled `*client`, the quic-go transport, the `http3.Transport`, and every goroutine `AndroidTun.Close()` fails to stop (`tun_android.go:47-49`) — belongs to the Go runtime.

They are currently in the same process for no reason.

```
:vpn  (foreground service, VpnService)          :xray  (bound service, hosts libXray)
  owns tunInterface — NEVER closed                loads libXray.so, runs xray-core
  owns ConnectionParams, network callbacks        holds a dup of the tun fd (via Binder PFD)
  owns the honest probe + the recovery ladder     calls back for protect()
  supervises :xray, kills and rebinds it          dies and is reborn freely
```

**Recovery becomes:** `bindService(:xray)` → hand it a fresh `tunInterface.dup()` as a `ParcelFileDescriptor` → `start(runtimeJson)` → probe → on failure, `Process.killProcess(xrayPid)` (or `unbindService`) and repeat. `:vpn` never dies.

Properties:

- **Leak window: zero.** The tun and every netd rule stay up for the whole operation. Traffic during the gap is dropped at the tun, not sent in the clear. This alone retires D16.
- **The hysteria pool is destroyed by construction.** No fork, no `CloseAll()`, no exported-symbol reachability argument, no D15 nil-deref race, no permanent maintenance obligation against upstream bumps.
- **A Go panic in xray stops being process death for the VPN.** Today it kills `:vpn` and the tun with it. This is a strictly larger reliability win than the reconnect fix itself.
- **AMS restart backoff does not apply.** `:xray` is a *bound* service supervised by a live foreground service, not a service AMS restarts on its own timer. `:vpn` observes death via `DeathRecipient`/`onServiceDisconnected` and rebinds immediately. No `START_STICKY`, no exact alarms, no FGS background-start rules, no broadcast dispatch — the entire stack of coin flips in the analysis §"Reliability argument" is deleted, and the `:vpn` process is exactly what a foreground service is for.
- **Cost: ~300 ms.** Zygote fork 80-150 ms, `dlopen(libXray.so)` 50-150 ms, xray start + fresh dial 190-272 ms.

**Double-buffer it and the downtime approaches zero.** Declare two processes (`:xrayA`, `:xrayB`) and alternate. Spawn and bind B *while A is still carrying traffic* (~300 ms, fully overlapped), then `stopXray` in A → `setTunFd` + `start` in B → probe → kill A. Real downtime shrinks to the `stopXray`→`start` gap, ~200-300 ms, and the tun never closes at any point.

**The one thing to verify before committing:** `protect()`. `VpnService.protect(int)` is an instance method and the `Vpn.protect()` path checks the calling UID. `:xray` is the same UID, so a direct call *may* work — but do not rely on it. The safe default is an AIDL callback: `:xray`'s `ProtectFdInvocationHandler` calls into `:vpn`, which holds the `VpnService` instance and calls `protect()`. Cost is one extra synchronous Binder hop per socket creation. Note this is a ~2× on an operation that is *already* a Binder call to system_server plus a JNI round trip today, and it does not sit on the packet path — only on connection setup. Acceptable. Fix `registerProtectControllers` idempotency (D17, `PlatformXrayEngine.android.kt:103`) regardless — with a fresh process per recovery it becomes naturally idempotent, which is a further argument for the split.

**Honest failure modes of this design:**
1. Per-socket Binder latency on connection-heavy workloads (many short-lived outbounds). Measure it; if it hurts, test the direct-`protect()`-from-`:xray` path.
2. `:xray` is not itself a foreground service. Being bound by an FGS gives it high `oom_adj`, but an aggressive OEM LMK can still take it. Mitigation is the supervisor loop — and unlike today, its death is *detected instantly and recovered in 300 ms with no leak*.
3. Passing the tun fd over Binder is one more moving part; a `ParcelFileDescriptor` dup that leaks in `:xray` is bounded by that process's lifetime, which is the point.
4. It does not fix the lying probe, the cross-process recovery routing, or the trigger set. Steps 1, 4 and 5 of the analysis are still required. This replaces **Step 2 (the fork) and Step 3's tun handling**, and it makes T2/T3 of the ladder collapse into T1.

---

## 4. Sanity checks on the other two experts

**Go expert on fd ownership — CONFIRMED, and stronger than stated.** The claim that `protectFd` receives a valid fd is right, but the load-bearing half is the *upper* bound: `syscall/net.go:11-12` and `internal/poll/fd_posix.go:56-63` make "valid during `f`, invalid after" a documented and enforced contract. Any design that stores the raw int is unsound. The `manger` reachability claim also holds: `manger` is package-level unexported (`hysteria/dialer.go:437`), and `clientManager.clean()` (`:428-435`) never deletes map entries — it calls `c.clean()` (`:332-339`), which closes only clients already `StatusInactive`. Entries accumulate forever. D14 stands. Note also `c.close()` (`:141-147`) dereferences `c.conn` with no nil guard — `clean()` is safe only because `status()` returning `StatusInactive` implies `conn != nil`. Any exported `CloseAll()` would have to replicate that, which is a further argument against the fork.

**Android expert on alarms/FGS — the reasoning is sound but the whole subtree is now moot.** `setExactAndAllowWhileIdle` quota (~1 per 9-10 min in deep Doze), the `USE_EXACT_ALARM` Play-policy restriction, and AMS `SERVICE_RESTART_DURATION` ×4 escalation are all correctly characterised, and D9/D10's rejection of them as a primary engine is right. Under the §3.2 split, none of them are on any path: there is no process to restart from outside, because the supervisor is alive.

**Two factual corrections to the analysis document:**

1. **`WAKE_LOCK` is NOT absent.** The doc states at Step 4 that it must be added and is "verified absent today". It is present at `androidApp/src/main/AndroidManifest.xml:13`. (The manifest is modified in the working tree, so this may have landed after the analysis was written — but the instruction as written is stale.)
2. **`FOREGROUND_SERVICE_SPECIAL_USE` is declared** (`:7`), so D18's open question about FGS type is already answered in the tree; what remains open is only Play review acceptance of `specialUse` subtype `vpn`.

---

## 5. Recommendation

1. **Drop the fd-shutdown idea.** It is unsafe as originally specified (§1.1), safe-able via `dup()` (§1.2), and *ineffective either way* because the socket is unconnected UDP (§1.3). Do not spend a build on it.
2. **Drop the xray-core fork.** §3.2 gets the same pool-eviction guarantee for less code and no upstream debt.
3. **Ship the `:xray` process split.** It is the owner's "just restart the process" instruction, applied to the correct process — with a zero leak window instead of 22.2 s, ~300 ms instead of seconds-to-minutes, and no dependency on AMS, alarms, broadcasts, or OEM policy.
4. Steps 1 (honest probe), 4 (recovery stays in `:vpn`) and 5 (trigger set) from the analysis are unchanged and still required. Step 0 instrumentation still ships first — nothing here is measurable until the probe stops lying.

**Files that change:** `androidApp/src/main/AndroidManifest.xml` (declare `:xray` bound service), `core/xray/src/androidMain/kotlin/com/onthecrow/deltavpn/xray/PlatformXrayEngine.android.kt` (becomes an AIDL client in `:vpn`, with the current body moving into the `:xray` service), `core/xray/src/androidMain/kotlin/com/onthecrow/deltavpn/xray/AndroidVpnSocketProtector.kt` (becomes the AIDL callback target), `core/vpn/impl/src/androidMain/kotlin/com/onthecrow/deltavpn/vpn/DeltaVpnService.kt` (supervisor + `runRedial()`; `stopTunnel()` leaves `runConnect`). No Go changes.
