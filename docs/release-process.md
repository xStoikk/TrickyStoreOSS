# Release process (design)



Canonical fork: **xStoikk/TrickyStoreOSS**.



## Current automation state



| Component | xStoikk fork behavior |

|-----------|----------------------|

| `Build` workflow | Full acceptance gate; CI artifacts on green builds |

| `Release` workflow | **Inert** — upstream repository guard |

| `changelogs.yml` | **Inert** — upstream repository guard |

| `release-fork.yml` | **Manual draft-only** — `workflow_dispatch` on `main` only |

| Local validation | `pwsh scripts/validate-release.ps1` (cross-platform Gradle wrapper) |

| Release prep | `pwsh scripts/prepare-release.ps1 -Tag v3.2.0-oss.1` |



Upstream release automation remains inert on xStoikk. Fork publishing uses separate `release-fork.yml`.



## Protected `main` ruleset



| Rule | Setting |

|------|---------|

| Restrict deletions | On |

| Block force pushes | On |

| Require linear history | On |

| Require status check **`build`** | On |

| Bypass actors | None |



Public releases and versionCode derivation require protected `main` linear history.



## CI trigger policy (`Build` workflow)



| Event | paths-ignore | Build runs when |

|-------|--------------|-----------------|

| **push** → `main` | `**.md`, `update.json` | Any non-doc, non-update.json change |

| **pull_request** → `main` | *(none)* | **Always** |

| **workflow_dispatch** | — | Always |



## Acceptance before release



See [release-validation.md](release-validation.md).



1. Clean tree on **protected `main`**

2. `pwsh scripts/validate-release.ps1`

3. `pwsh scripts/prepare-release.ps1 -Tag v3.2.0-oss.1`



Output: `out/release-prep/` (`schemaVersion: 2`).



### Tag / product coherence



`-Tag` must match packaged product version. Mismatch fails nonzero with no override.



## Version metadata



| Field | Build input | Release prep truth (after validate) |

|-------|-------------|-----------------------------------|

| Product version | `gradle.properties` → `trickyStoreVersionName` | Packaged `module.prop` in Release ZIP |

| versionCode | `trickyStoreVersionCodeEpoch + commitCount` | Packaged `module.prop` |

| commitCount (provenance) | `git rev-list HEAD --count` | Git HEAD; ZIP/display suffix |

| ZIP count segment | commit count (not epoch) | Release ZIP filename |

| Module author | `module/module.prop` template | Packaged `module.prop` |

| updateJson (packaged) | `module/module.prop` template | Packaged `module.prop` |

| TeeBuildInfo.PHASE | `teeBuildPhase` in `app/build.gradle.kts` | Generated `TeeBuildInfo.kt` |



**Authorized product version:** `v3.2.0-oss.1`

**Public Git tag:** `v3.2.0-oss.1` (must match product version)

**versionCode epoch:** `100000`



## Fork draft release workflow



File: `.github/workflows/release-fork.yml`



| Property | Value |

|----------|-------|

| Trigger | `workflow_dispatch` only |

| Repository | `xStoikk/TrickyStoreOSS` only |

| Source ref | `refs/heads/main` only |

| Draft | **Always** (no publish input) |

| Build | `pwsh ./scripts/prepare-release.ps1 -Tag "<input>"` |

| Attestation | `actions/attest@v4` on Release ZIP |

| Mutations | **None** to tracked `update.json`, `changelog.md`, or `main` |



Safety checks before build:



- Tag syntax validation

- Tag == `trickyStoreVersionName`

- Remote tag must not exist

- GitHub Release for tag must not exist



Attaches: Release ZIP, `release-manifest.json`, `update.json.next`, `release-summary.md`.



**Does not attach Debug ZIP.**



Creating the draft may create Git tag ref at release SHA. Upstream `release.yml` stays skipped on fork.



## update.json ownership



1. `release-fork.yml` produces draft release + `out/release-prep/update.json.next`

2. Maintainer opens PR committing tracked `update.json` via protected `main` (green Build)

3. Verify raw fork feed on `main`

4. Publish draft release manually



Tracked `update.json` remains upstream metadata until the metadata PR lands **before** publication.



## update.json / versionCode



KernelSU uses integer `versionCode` for update comparison.



Fork epoch **`100000 + commitCount`** isolates xStoikk ordering from upstream's independent history (upstream tracked feed: versionCode **172**).



Monotonicity guarantee applies within xStoikk's own protected-main release policy, not against arbitrary external schemes.



## Related docs



- [release-identity.md](release-identity.md)

- [release-validation.md](release-validation.md)

- [upstream-sync.md](upstream-sync.md)
