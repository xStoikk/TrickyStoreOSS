# Phase 4C — Moto G 2025 BASIC Integrity Acceptance

**Status:** Accepted (3/3 cold boots)  
**Tag:** `phase4c-basic-integrity-moto-accepted`  
**Build:** `v3.1.6-auto-tee-passthrough` (phase=4C)

## Accepted artifact

| Item | Value |
|---|---|
| ZIP | `out/Tricky-Store-OSS-v3.1.6-auto-tee-passthrough-172-41383f5-Release.zip` |
| ZIP SHA256 | `004946AB4D94215D0833E1B6275CBADB6371A8BD16035DDD6BB620B18F28E025` |
| classes.dex SHA256 | `7018DB6FF0E7AE65CA1E3CD42061E7D47AC6DAC6B05923F99448CD293A8C3D17` |
| BUILD_ID | `version=v3.1.6-auto-tee-passthrough git=41383f5 phase=4C` |

Device: Moto G 2025, Android 16, KernelSU Next, ReZygisk. No PIF, no legacy TrickyStore, no TEESimulator.

## Problem history

1. **False early-boot `teeBroken`** — Background TEE probe could classify transient boot-time failures as permanent, writing `teeBroken=true` and forcing software certificate generation on hardware-capable devices.

2. **Repeated `initializeMainlineModules` retry bug (Phase 4B)** — Each probe retry re-ran `ActivityThread.initializeMainlineModules()` + `AndroidKeyStoreProvider.install()`, triggering `IllegalStateException: setTelephonyServiceManager called twice!` before `generateKeyPair()` could run. Fixed with one-shot `TeeProbeBootstrap`.

3. **Capability-vs-policy routing coupling (Phase 4C)** — `TeeState` (UNKNOWN / WORKING / BROKEN) was used both as a capability indicator and as AUTO certificate-transformation policy. When Phase 4B correctly promoted UNKNOWN→WORKING, AUTO routing switched from passthrough to `leaf-forward-attestation` + `CertificateHack`, breaking `MEETS_BASIC_INTEGRITY` on Moto despite real TEE success.

## Final architecture

- **Tri-state `TeeState`** with transient/permanent classifier and bounded retry policy (`TeeProbeClassifier`, `TeeRetryPolicy`, `TeeProbeAttemptCoordinator`).
- **One-shot probe bootstrap** (`TeeProbeBootstrap`) separating bootstrap from per-attempt hardware attestation.
- **Persistent probe lifecycle trace** (`tee_phase3_trace.log` via `TeePhase3Trace`).
- **Decoupled AUTO routing** (`GenerateKeyRoute`): AUTO + `!needGenerate` → `passthrough-real-tee` for both UNKNOWN and WORKING; BROKEN / `needGenerate` → generate; explicit `?` leaf → leaf-forward; explicit `!` → generate.
- **`PassthroughKeyRegistry`** with generateKey tracking, getKeyEntry early bypass, deleteKey cleanup; stale patched-cache cleared on passthrough ownership.
- **Device-ID attestation alone** does not force forge.
- **Shell script LF normalization** via `.gitattributes`.

## Moto acceptance criteria (3/3 cold boots)

- Probe bootstrap succeeds; real KeyPairGenerator retries occur.
- `TeeState` reaches WORKING; `teeBroken=false`.
- Play Store / GMS AUTO requests: `selected=passthrough-real-tee`.
- `PASSTHROUGH_RESULT result=success reply_unchanged=true`; no certificate mutation.
- `MEETS_BASIC_INTEGRITY = PASS`.

## Known limitations

- Debug log may still emit misleading `"proceeding with leaf hack"` before passthrough post-policy resolves (cosmetic only).
- No Device Integrity or Strong Integrity work in this phase.
- Post-checkpoint rebuilds change `module.prop` version suffix (commit count/hash) but preserve `classes.dex` when built from the tagged tree with release-only assembly.
