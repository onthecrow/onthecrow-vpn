# DeltaVPN

Kotlin Multiplatform / Compose Multiplatform VPN client (Android, iOS/macOS via NetworkExtension,
desktop JVM, Windows). Outbound is hysteria2 over QUIC via libXray (xray-core). Purpose: bypassing
regional blocking. Not an anonymity product, but traffic must never silently leak outside the tunnel.

## Working agreements

- The user commits and pushes themselves. Do not commit or push unless asked.
- The client only ever talks to Firestore (bundle existence). It knows nothing about xray servers.
- Trust the user's empirical observations over any assumption. If the user says the client did not
  switch, it did not — investigate, do not rationalise.
- Reliability outranks latency. A fix that cannot be verified on a device is worth less than one that
  can; prefer "here is the measurement that would confirm it" over a confident guess.

## VPN internals — read before touching VPN code

The VPN service, tunnel, libXray integration, and network-change / Doze recovery are documented:

- [`.claude/vpn-service.md`](.claude/vpn-service.md) — **full internal map** (Claude-facing): process
  topology, recovery ladder, quicParams, tun rebuild, triggers, probe, IPC/engine singleton, the
  underlying-network state machine, and the list of historical bugs + disproved diagnoses. **Start
  here** before editing anything under `core/vpn/impl/src/androidMain` or `core/xray/src/androidMain`.
- [`docs/vpn-recovery.md`](docs/vpn-recovery.md) — developer-facing overview of recovery on network
  change and Doze.
- [`docs/vpn-architecture-audit.md`](docs/vpn-architecture-audit.md) — the three-specialist audit and
  its open questions (what still needs a device to answer).
- [`docs/android-doze-battery-plan.md`](docs/android-doze-battery-plan.md) — the battery/Doze tradeoff.

**When you change VPN behaviour, update the docs in the SAME change** — both
[`.claude/vpn-service.md`](.claude/vpn-service.md) and [`docs/vpn-recovery.md`](docs/vpn-recovery.md).
They exist to stop the next session re-deriving facts that cost days of field debugging; a stale doc is
worse than none. If a change invalidates a documented fact (a constant, a rung, a trigger, a rationale),
fix the doc, not just the code.

## Analytics

Product analytics goes through the cross-platform **`core:analytics`** module: call an
`AnalyticsManager` method at the point an event happens. It forwards to `core:firebase`'s
`AnalyticsTracker` (real Firebase on Android/iOS/macOS, log on JVM). The 8 `vpn_*` service events are
still wired in `androidMain` only (they are Android-service-specific), but the module itself targets
android/ios/macos/jvm. The event plan, the per-event specs, the deny-list, and the implementation status
live in [`docs/analytics-events.md`](docs/analytics-events.md).

**Privacy invariant (this is a VPN):** analytics carries outcomes, enums, booleans and bucketed
counts/durations ONLY — never a server address/port, credential, config URL, destination,
subscription/bundle id, split-tunnel package, or any raw count/timestamp/message. The typed API enforces
this (no free-form `String` params). Never call `AnalyticsManager` from the `:xray` process (Firebase is
only initialised in the main process). The 8 `vpn_*` service events fire from `DeltaVpnService`
(a `KoinComponent` for this) — keep their `RecoveryTrigger`/`RecoveryOutcome`/`EngineDeathReason`/`ConnectVia`
enums consistent with the recovery ladder in `.claude/vpn-service.md`.

## Error reporting

Caught, **unexpected** non-fatals go to Crashlytics through the cross-platform **`core:error-reporting`**
module (parallel to `core:analytics`): `ErrorReporter.report(domain, throwable)` → internal impl →
`core:firebase` `CrashReporter.recordException`. The impl **scrubs the throwable's message** (keeps type
+ stack + a bounded `ErrorDomain`) because a caught message routinely carries a server/credential/URL;
`setUserId` is never called. Report actionable failures only — NOT expected control-flow (probe
timeouts, close/flush cleanups, lookup fallbacks). `core:error-reporting` targets android/ios/macos/jvm
with **real Crashlytics on Android/iOS/macOS** (macOS via the GitLive KMP SDK — `core:firebase` has a
`macosMain`) and stdout on JVM (Firebase Crashlytics has no JVM SDK). `core:error-reporting` OWNS the
`CrashReporter` port (moved out of `core:firebase` so it carries no Firebase graph); the app binds the
full Firebase surface via `core:firebase`, the iOS **NE** binds a **Crashlytics-only** surface via
**`core:firebase-crashlytics`** (links only the Crashlytics closure — never Firestore/gRPC — so the appex
stays slim). The `:xray` process never reports (no Firebase). `core:error-reporting` itself is now
Firebase-free, so it is safe anywhere; the hard rule is that **`core:firebase` (or `core:analytics`, which
pulls it) must never reach the iOS NE appex** — the NE binds `CrashReporter` only via
`core:firebase-crashlytics`, which is why `core:xray` keeps its error-reporting dep in
`androidMain`/`jvmMain` only. Full plan, wired sites, the module split, and the
required Xcode/plist steps: [`docs/error-reporting.md`](docs/error-reporting.md).

## Build

```bash
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease test
```

The KMP feature-module unit tests are NOT run by the bare `test` task — run them explicitly, e.g.
`./gradlew :feature:connection:ui-impl:jvmTest :feature:connection:logic-impl:jvmTest :feature:settings:ui-impl:jvmTest :core:analytics:jvmTest`.

`:core:xray:assemble` fails on an unrelated pre-existing jvm-target issue — build the app, not that task.
