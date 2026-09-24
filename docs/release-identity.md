# Fork release identity

Canonical fork: **xStoikk/TrickyStoreOSS**  
Upstream: **beakthoven/TrickyStoreOSS** (fetch-only)

Phase 7C documents identity without changing Java/Kotlin package names or publishing a release.

## Three identity layers

| Layer | Value | Phase 7C change |
|-------|-------|-----------------|
| **Upstream authorship** | beakthoven, original Tricky Store OSS | Preserved in copyright, history, upstream attribution |
| **Fork maintainer** | xStoikk | Documented; release metadata uses `xStoikk/TrickyStoreOSS` |
| **Technical package ID** | `tricky_store`, `io.github.beakthoven.TrickyStoreOSS` | **Unchanged** — out of scope |

## Current release identity (audit @ `9816553`)

| Field | Source | Current value |
|-------|--------|---------------|
| A. Module display name | `module/module.prop` → packaged | `Tricky Store OSS` |
| B. Module ID | `module/module.prop` | `tricky_store` |
| C. Module author | `module/module.prop` | `beakthoven` |
| D. applicationId | `app/build.gradle.kts` | `io.github.beakthoven.TrickyStoreOSS` |
| E. namespace | `app/build.gradle.kts` | `io.github.beakthoven.TrickyStoreOSS` |
| F. verName | `gradle.properties` → `trickyStoreVersionName` | `v3.1.6-auto-tee-passthrough` |
| G. versionCode | `git rev-list HEAD --count` | Integer commit count (derived at build) |
| H. ZIP filename | `app/build.gradle.kts` `zip*` task | `Tricky-Store-OSS-{verName}-{count}-{sha}-{Variant}.zip` |
| I. updateJson (packaged) | `module/module.prop` template | `https://raw.githubusercontent.com/beakthoven/TrickyStoreOSS/main/update.json` |
| J. update.json version | tracked `update.json` | `v3.1.0` |
| K. update.json versionCode | tracked `update.json` | `172` |
| L. update.json zipUrl | tracked `update.json` | upstream Release asset URL |
| M. changelog URL | tracked `update.json` | upstream `changelog.md` raw URL |
| N. TeeBuildInfo.VERSION | generated from `trickyStoreVersionName` | same as F |
| O. TeeBuildInfo.GIT | generated short SHA (+ `-dirty`) | build-time HEAD |
| P. TeeBuildInfo.PHASE | `teeBuildPhase` in `app/build.gradle.kts` | `4C` |

Packaged `module.prop` version format: `{verName} ({count}-{sha}-release)`.

## Recommended module author (first fork release)

**Do not change `author=` in Phase 7C.**

Recommended first public fork release display:

**`xStoikk (fork; upstream by beakthoven)`**

Rationale: preserves upstream credit, signals fork maintainer, avoids implying beakthoven publishes xStoikk builds.

## Version source of truth

| Field | Authority |
|-------|-----------|
| Product version string | `gradle.properties` → `trickyStoreVersionName` |
| versionCode | `git rev-list HEAD --count` |
| Runtime architecture phase | `teeBuildPhase` in `app/build.gradle.kts` (not project phase number) |

Release tooling reads `gradle.properties` without parsing Kotlin.

## TeeBuildInfo.PHASE

`PHASE=4C` is the **runtime architecture milestone** embedded at build time. It is **not** the engineering phase label (7A, 7B, 7C). No change in Phase 7C — architecture contract unchanged.

## Tag / product version invariant

**Git release tag must equal packaged product version.**

Example packaged `module.prop` line:

`version=v3.1.6-auto-tee-passthrough (192-abcdef0-release)`

Canonical product version: `v3.1.6-auto-tee-passthrough`

`prepare-release.ps1 -Tag` fails if Tag ≠ product version extracted from the validated Release ZIP.

`update.json.next` uses product version for `version` and Tag for `zipUrl` path (equal when validation passes).

## Post-7C dry-run (current product version)

Until `trickyStoreVersionName` is authorized to change:

```powershell
pwsh scripts/prepare-release.ps1
```

**Without `-Tag`.** Produces `release-manifest.json` and `release-summary.md` only.

After authorizing **`v3.2.0-oss.1`** in `gradle.properties` and rebuilding from protected `main`:

```powershell
pwsh scripts/prepare-release.ps1 -Tag v3.2.0-oss.1
```

## First public fork release — update feed policy

**The first public xStoikk fork release must already point at the fork feed:**

`https://raw.githubusercontent.com/xStoikk/TrickyStoreOSS/main/update.json`

Do **not** implement that URL change in Phase 7C. Development builds remain upstream-feed until the deliberate cutover release-prep branch.

### Safe first-public-release sequence

| Step | Action |
|------|--------|
| A | Release-prep branch: authorize public version (`v3.2.0-oss.1`), adopt versionCode strategy, change `module.prop` `updateJson` to xStoikk feed → PR → green Build → FF to protected `main` |
| B | Prepare exact artifact from protected `main` (`validate-release` + `prepare-release -Tag …`) |
| C | Create **draft** GitHub Release for exact tag/SHA; upload validated Release ZIP |
| D | Generate candidate fork `update.json` from exact artifact/tag |
| E | Verify tag, version, versionCode, ZIP name, SHA, asset URL |
| F | Commit tracked `update.json` via branch → PR → green Build → FF |
| G | Verify raw fork `update.json` on `main` |
| H | Publish draft GitHub Release |

Result: first public release module and fork feed describe the same release — **no second feed-migration release**.

## Future public version / tag pair (not yet authorized)

| Field | Future value |
|-------|----------------|
| Product version (`trickyStoreVersionName`) | `v3.2.0-oss.1` |
| Git tag | `v3.2.0-oss.1` |

They must move together. Do **not** tag `v3.2.0-oss.1` while product version remains `v3.1.6-auto-tee-passthrough`.

## versionCode strategy (recommendation — not final)

**Development:** continue `git rev-list HEAD --count`.

**Before public fork feed:** evaluate fork epoch, e.g. **`100000 + commitCount`** — not implemented in Phase 7C.

Public releases must be cut **only from protected `main`** (linear canonical history); commit-count versioning is meaningless on arbitrary feature branches.
