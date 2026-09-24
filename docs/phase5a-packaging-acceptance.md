# Phase 5A — Debug/Release Packaging Isolation Acceptance

**Status:** Accepted (build matrix + Moto device sanity)  
**Tag:** `phase5a-packaging-isolation-accepted`  
**Parent:** `phase4c-basic-integrity-moto-accepted` (`a465498`)

Build-only phase. No runtime / integrity behavior changes.

## Problem

`copyFilesDebug` / `copyFilesRelease` wrote generated artifacts (`service.apk`, `classes.dex`, `lib/*`) into the shared source-controlled `module/` directory. `prepareModuleFiles*` then walked all of `module/` when assembling ZIPs.

When Debug ran before Release in the same Gradle invocation, Debug's `service.apk` (~12 MB) remained in `module/` and was packaged into the Release ZIP (roughly 1.3 MB → 6.4 MB). The reverse could leak Release `classes.dex` into Debug.

## Solution

- **Variant-isolated staging:** `app/build/moduleStage/debug/` and `app/build/moduleStage/release/`
- **Static inputs only from `module/`:** generated artifact names and `lib/` excluded when copying source tree
- **`prepareModuleFiles*`:** merges static `module/` inputs with per-variant stage into `app/build/tmp/module-{variant}/`
- **Verification:** `verifyReleaseModuleContents` / `verifyDebugModuleContents` consume exact `zipTask.archiveFile` providers (not newest file in `out/` by mtime)

## Build matrix (all PASS)

| Scenario | Release | Debug |
|---|---|---|
| Release only | PASS | — |
| Debug only | — | PASS |
| Release + Debug | PASS | PASS |
| Debug + Release | PASS | PASS |
| `--parallel` Release + Debug | PASS | PASS |

Stale-artifact test: newer-mtime decoy Release ZIP with `service.apk` in `out/` does not affect verification.

## Accepted tested artifact

| Item | Value |
|---|---|
| ZIP | `out/Tricky-Store-OSS-v3.1.6-auto-tee-passthrough-173-a465498-Release.zip` |
| ZIP SHA256 | `5A7655C77E5C93DD1CCC6A870EF142BFCEF918EC1BEDE4931C5FA59D7F11A168` |
| classes.dex SHA256 | `7018DB6FF0E7AE65CA1E3CD42061E7D47AC6DAC6B05923F99448CD293A8C3D17` (unchanged from Phase 4C) |
| module.prop | `v3.1.6-auto-tee-passthrough (173-a465498-release)` |

## Device sanity (Moto G 2025)

Single-boot sanity check on Phase 5A Release ZIP (not full 3-boot gate):

- `teeBroken=false`
- Play Store / GMS: `selected=passthrough-real-tee`, passthrough success, no certificate mutation
- `MEETS_BASIC_INTEGRITY = PASS`
- Device/Strong Integrity not in scope (expected FAIL)

## Known limitations

- Old ZIPs may accumulate in `out/` until manually removed (harmless; verify uses task output binding)
- Release ZIP byte hash differs from Phase 4C Moto artifact (`004946AB…`) despite identical `classes.dex` and size — ZIP metadata/ordering from new staging path
