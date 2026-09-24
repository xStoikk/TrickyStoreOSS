# Release validation gate

Acceptance checklist for Tricky Store OSS module releases.

## Automated enforcement (Phase 7B)

| Environment | Mechanism |
|-------------|-----------|
| **GitHub Actions** | `.github/workflows/build.yml` — full Gradle suite on push/PR to `main` |
| **Local (Windows)** | `pwsh scripts/validate-release.ps1` |

Both enforce:

```text
testDebugUnitTest
assembleRelease
assembleDebug
verifyReleaseModuleContents
verifyDebugModuleContents
lintRelease
```

Local script additionally requires a **clean git tree** and runs `clean` first.

### Verifier redundancy

`assembleRelease` / `assembleDebug` already `finalizedBy` `verify*ModuleContents` via Gradle. CI lists verifiers **explicitly** as a human-readable release contract — redundant but intentional.

## SOURCE

- [ ] Clean git tree (`git status` shows no uncommitted changes)
- [ ] Known commit SHA recorded in release notes / tag
- [ ] `TeeBuildInfo.GIT` matches commit short SHA in built artifact
- [ ] Version metadata reviewed (see [release-process.md](release-process.md))

## TEST

- [ ] All unit tests pass (baseline: 142+)
- [ ] No routing-behavior test regressions without documented contract change

## BUILD

- [ ] `assembleRelease` PASS
- [ ] `assembleDebug` PASS
- [ ] `verifyReleaseModuleContents` PASS
- [ ] `verifyDebugModuleContents` PASS
- [ ] `lintRelease` PASS

## ZIP (Release module)

Gradle `verifyReleaseModuleContents` checks:

- [ ] `classes.dex` present at ZIP root
- [ ] `service.apk` **absent** from Release ZIP
- [ ] `module.prop` present; variant token (`release`) in content
- [ ] Exactly **one** Release ZIP in `out/` after build

Gradle `verifyDebugModuleContents` checks:

- [ ] `service.apk` present
- [ ] `classes.dex` absent (no Release/Debug staging leak)

ZIP naming: `Tricky-Store-OSS-{verName}-{commitCount}-{shortSha}-{Variant}.zip`

Additional layout checks (19 files / 9 dirs / 28 entries) are enforced indirectly by stable packaging tasks; extend Gradle verifier if stricter counts become required.

## DEVICE (manual smoke — not CI-gated)

Optional but recommended before publishing — see Phase 7A device section.

## PRODUCTION

- [ ] No claims of DEVICE/STRONG spoofing in release notes
- [ ] No automatic artifact publish without maintainer review
- [ ] Tag annotated when shipping

## CI artifact discovery

CI clears `out/` before build and **fails** if Release or Debug ZIP count ≠ 1.

## CI trigger policy

`Build` workflow `paths-ignore` (push/PR to `main`):

- `**.md` — docs-only changes may skip CI
- `update.json` — metadata-only changes may skip CI

Everything else runs CI, including:

- `.github/workflows/build.yml` and all workflow edits (workflows are **not** path-ignored)
- `scripts/**`
- application and Gradle sources

GitHub `paths-ignore` is exclusion-only; do not rely on negated re-inclusion patterns.

## Fork release safety

Inherited `release.yml` and `changelogs.yml` publish only when `github.repository == 'beakthoven/TrickyStoreOSS'`. On xStoikk/TrickyStoreOSS, `v*` tag pushes do not create releases or mutate `update.json`.

## CI alignment

Phase 7B closes the Phase 7A gap: unit tests + verifiers run in `Build` workflow.
