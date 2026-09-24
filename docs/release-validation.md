# Release validation gate



Acceptance checklist for Tricky Store OSS module releases.



## Automated enforcement



| Environment | Mechanism |

|-------------|-----------|

| **GitHub Actions** | `.github/workflows/build.yml` |

| **Local validation** | `pwsh scripts/validate-release.ps1` |

| **Release prep (dry-run)** | `pwsh scripts/prepare-release.ps1 -Tag v3.2.0-oss.1` |

| **Fork draft release** | `.github/workflows/release-fork.yml` (manual, main-only) |



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

- uses cross-platform Gradle wrapper (`gradlew.bat` on Windows, `./gradlew` elsewhere)

- runs `clean` first

- clears `out/` before build (mirrors CI)

- deletes temporary ZIP extraction files after hashing



## SOURCE



- [ ] Clean git tree

- [ ] Known commit SHA recorded

- [ ] `trickyStoreVersionName` = `v3.2.0-oss.1`

- [ ] `trickyStoreVersionCodeEpoch` = `100000`

- [ ] `TeeBuildInfo.GIT` matches packaged short SHA



## TEST / BUILD / ZIP



Baseline **142+** unit tests.



ZIP naming: `Tricky-Store-OSS-{verName}-{commitCount}-{shortSha}-{Variant}.zip`



`versionCode` = **`100000 + git rev-list HEAD --count`** at build commit.



Packaged `module.prop` verifies (Gradle):



- `versionCode` == epoch + commit count

- `version` contains product version, short SHA, variant

- `author` == `xStoikk (fork; upstream by beakthoven)`

- `updateJson` == xStoikk fork feed URL



## CI trigger policy



| Event | Skips CI when |

|-------|----------------|

| **push** → `main` | Only `**.md` or `update.json` changes |

| **pull_request** → `main` | **Never** |



## prepare-release.ps1



Execution order:



1. Clean git tree (fail before any output mutation)

2. `validate-release.ps1` (clears `out/`, builds artifacts)

3. Remove stale `out/release-prep/` if present

4. Write manifest (`schemaVersion: 2`) and summary from **artifact-derived** `module.prop`

5. With `-Tag`: write `update.json.next` only if Tag == packaged product version



Manifest fields include `versionCodeStrategy: epoch+commitCount` and `versionCodeEpoch: 100000`.



`generatedAtUtc` is audit metadata; deterministic anchors are ZIP and `classes.dex` SHA256.



## Fork release safety



- Inherited `release.yml` / `changelogs.yml` run only on `beakthoven/TrickyStoreOSS`

- `release-fork.yml` is separate, draft-only, does not commit tracked metadata



## Protected main



Require **`build`** status check. See [release-process.md](release-process.md).
