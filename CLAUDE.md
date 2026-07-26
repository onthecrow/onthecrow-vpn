# OnthecrowVPN

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

## Build

```bash
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease test
```

`:core:xray:assemble` fails on an unrelated pre-existing jvm-target issue — build the app, not that task.
