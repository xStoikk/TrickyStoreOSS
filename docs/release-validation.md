# Release validation gate

Acceptance checklist for Tricky Store OSS module releases. Phase 7A defines the gate; future phases execute it.

## SOURCE

- [ ] Clean git tree (`git status` shows no uncommitted changes)
- [ ] Known commit SHA recorded in release notes / tag
- [ ] `TeeBuildInfo.GIT` matches commit short SHA in built artifact
- [ ] Version metadata reviewed (see versioning policy — no silent bump without authorization)

## TEST

```powershell
.\gradlew.bat testDebugUnitTest
```

- [ ] All unit tests pass (baseline: 142+)
- [ ] No routing-behavior test regressions without documented contract change

## BUILD

```powershell
.\gradlew.bat clean assembleRelease assembleDebug verifyReleaseModuleContents verifyDebugModuleContents lintRelease
```

- [ ] `assembleRelease` PASS
- [ ] `assembleDebug` PASS
- [ ] `verifyReleaseModuleContents` PASS
- [ ] `verifyDebugModuleContents` PASS
- [ ] `lintRelease` PASS

## ZIP (Release module)

- [ ] Output under `out/*Release.zip`
- [ ] **19 files**, **9 directory entries**, **28 total** entries (per verifier)
- [ ] `classes.dex` present at ZIP root
- [ ] `service.apk` **excluded** from Release ZIP
- [ ] `module.prop` present with substituted version fields
- [ ] Native libs present: `lib/*/libTrickyStoreOSS.so`, `libinject.so` per ABI

Record for each release:

- ZIP path
- ZIP SHA256 (informational — may vary with timestamps)
- `classes.dex` SHA256 (should be stable for identical inputs)

## DEVICE (manual smoke — not CI-gated)

Optional but recommended before publishing:

- [ ] Module installs on target device / KernelSU
- [ ] `TS_DIAG BUILD_ID` matches expected git SHA
- [ ] TEE probe starts (`PROBE_BOOTSTRAP_SUCCESS` or deferred UNKNOWN with retries)
- [ ] Plain AUTO sanity: `selected=passthrough-real-tee`, `reply_unchanged=true` on untracked real getKeyEntry
- [ ] Explicit `!` sanity when needed: `selected=generate`, `SOFTWARE_SYNTHETIC`
- [ ] Return target to AUTO after explicit tests

**Not required:** Play Integrity synthetic testing, GMS modification, root exploit validation.

## PRODUCTION

- [ ] No claims of DEVICE/STRONG spoofing in release notes
- [ ] No automatic artifact publish without maintainer review
- [ ] Tag annotated locally or on remote per release policy

## CI alignment (recommended)

GitHub Actions should eventually mirror TEST + BUILD sections. Device steps remain manual.

Current gap: `.github/workflows/build.yml` runs `assembleRelease assembleDebug lintRelease` but not `testDebugUnitTest` or module verifiers — see Phase 7A CI audit.
