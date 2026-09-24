# Release validation gate

Acceptance checklist for Tricky Store OSS module releases.

## Automated enforcement

| Environment | Mechanism |
|-------------|-----------|
| **GitHub Actions** | `.github/workflows/build.yml` |
| **Local validation** | `pwsh scripts/validate-release.ps1` |
| **Release prep (dry-run)** | `pwsh scripts/prepare-release.ps1` (no `-Tag` until product version authorized) |

Gradle gates (CI and local validator):

```text
testDebugUnitTest
assembleRelease
assembleDebug
verifyReleaseModuleContents
verifyDebugModuleContents
lintRelease
```

Local validator additionally:

- requires **clean git tree**
- runs `clean` first
- clears `out/` before build (mirrors CI)

Explicit verifier tasks in CI are **redundant** with `assemble*` `finalizedBy` but declare the release contract intentionally.

## SOURCE

- [ ] Clean git tree
- [ ] Known commit SHA recorded
- [ ] `TeeBuildInfo.GIT` matches packaged short SHA
- [ ] `trickyStoreVersionName` in `gradle.properties` reviewed

## TEST / BUILD / ZIP

See Phase 7A checklist items — baseline **142+** unit tests.

ZIP naming: `Tricky-Store-OSS-{verName}-{commitCount}-{shortSha}-{Variant}.zip`

`versionCode` equals `git rev-list HEAD --count` at build commit.

## CI trigger policy

| Event | Skips CI when |
|-------|----------------|
| **push** → `main` | Only `**.md` or `update.json` changes |
| **pull_request** → `main` | **Never** — all PRs run Build (branch protection) |

## CI artifact discovery

CI clears `out/` before Gradle and fails if Release or Debug ZIP count ≠ 1.

## prepare-release.ps1

Execution order:

1. Clean git tree (fail before any output mutation)
2. `validate-release.ps1` (clears `out/`, builds artifacts)
3. Remove stale `out/release-prep/` if present
4. Write manifest (`schemaVersion: 1`) and summary from **artifact-derived** `module.prop`
5. With `-Tag`: write `update.json.next` only if Tag == packaged product version

Post-7C acceptance: run **without `-Tag`**. Future `-Tag v3.2.0-oss.1` valid only after `trickyStoreVersionName` bump to same value.

Manifest `generatedAtUtc` is audit metadata; deterministic anchors are ZIP and `classes.dex` SHA256.

## Fork release safety

Inherited `release.yml` / `changelogs.yml` jobs run only on `beakthoven/TrickyStoreOSS`.

## Protected main

Require **`build`** status check. See [release-process.md](release-process.md) for linear-history / exact-SHA flow.
