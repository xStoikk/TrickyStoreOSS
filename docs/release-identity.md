# Fork release identity



Canonical fork: **xStoikk/TrickyStoreOSS**

Upstream: **beakthoven/TrickyStoreOSS** (fetch-only)



Phase 7D authorizes first public fork product version and structural cutover without publishing a release.



## Three identity layers



| Layer | Value | Phase 7D change |

|-------|-------|-----------------|

| **Upstream authorship** | beakthoven, original Tricky Store OSS | Preserved in copyright, history, upstream attribution |

| **Fork maintainer** | xStoikk | Packaged `author=` and release metadata |

| **Technical package ID** | `tricky_store`, `io.github.beakthoven.TrickyStoreOSS` | **Unchanged** |



## Authorized release identity (Phase 7D)



| Field | Source | Value |

|-------|--------|-------|

| A. Module display name | `module/module.prop` | `Tricky Store OSS` |

| B. Module ID | `module/module.prop` | `tricky_store` |

| C. Module author | `module/module.prop` | `xStoikk (fork; upstream by beakthoven)` |

| D. applicationId | `app/build.gradle.kts` | `io.github.beakthoven.TrickyStoreOSS` |

| E. namespace | `app/build.gradle.kts` | `io.github.beakthoven.TrickyStoreOSS` |

| F. Product version | `gradle.properties` → `trickyStoreVersionName` | **`v3.2.0-oss.1`** |

| G. versionCode | `100000 + git rev-list HEAD --count` | Epoch **100000** + commit count |

| H. commitCount (provenance) | `git rev-list HEAD --count` | Engineering provenance only |

| I. ZIP filename | `zip*` task | `Tricky-Store-OSS-{verName}-{commitCount}-{sha}-{Variant}.zip` |

| J. updateJson (packaged) | `module/module.prop` | `https://raw.githubusercontent.com/xStoikk/TrickyStoreOSS/main/update.json` |

| K. update.json (tracked) | `update.json` on `main` | **Still upstream** `v3.1.0` / `172` until draft asset PR |

| L. TeeBuildInfo.VERSION | generated from `trickyStoreVersionName` | `v3.2.0-oss.1` |

| M. TeeBuildInfo.PHASE | `teeBuildPhase` in `app/build.gradle.kts` | **`4C`** (unchanged) |



Packaged `module.prop` version display: `{productVersion} ({commitCount}-{sha}-release)` — suffix uses **commit count**, not epoch versionCode.



## Build metadata distinction



| Concept | Purpose | Example |

|---------|---------|---------|

| **commitCount** | Engineering provenance in ZIP name and module display version | `195` |

| **versionCode** | KernelSU integer update ordering | `100195` |

| **productVersion** | Human release identity | `v3.2.0-oss.1` |

| **Git tag** | Public release pointer | `v3.2.0-oss.1` (must equal product version) |



ZIP count segment stays at raw commit count — not `100xxx`.



## versionCode epoch policy



**Formula:** `versionCode = 100000 + commitCount`



KernelSU requires integer `versionCode` for update comparison. The fork epoch isolates xStoikk update ordering from upstream's independent commit-count history.



**Within xStoikk release policy:** epoch fixed at **100000**; protected-main commit count increases monotonically → published versionCode increases monotonically. This does not claim collision-proof behavior against every possible external repository scheme.



Public releases must be cut **only from protected `main`**.



## Tag / product version invariant



Git release tag must equal packaged product version.



`prepare-release.ps1 -Tag` fails if Tag ≠ product version from Release ZIP `module.prop`.



Post-7D acceptance (clean tree on protected `main`):



```powershell

pwsh scripts/prepare-release.ps1 -Tag v3.2.0-oss.1

```



Generates `release-manifest.json`, `release-summary.md`, and `update.json.next`.



## Tracked update.json — transient mismatch



**Packaged** modules now point at the xStoikk fork feed.



**Tracked** `update.json` on `main` still describes upstream `v3.1.0` / `172` because no xStoikk release asset exists yet.



This is acceptable **only while Phase 7D is unpublished**. Before `v3.2.0-oss.1` goes public:



1. Create draft release via `release-fork.yml` (manual dispatch)

2. Commit tracked `update.json` from `out/release-prep/update.json.next` via PR → green Build → FF

3. Verify raw fork feed on `main`

4. Publish draft release



## First public fork release sequence



| Step | Action |

|------|--------|

| A | Phase 7D merged: version, epoch, author, packaged feed on protected `main` |

| B | Manual `release-fork.yml` dispatch on `main` → draft release + assets |

| C | Verify manifest, ZIP, candidate `update.json.next` |

| D | PR tracked `update.json` → green Build → FF to `main` |

| E | Verify raw fork `update.json` |

| F | Publish draft GitHub Release |



First public release ships with fork feed in module **and** fork feed describing that release — no second migration release.



## TeeBuildInfo.PHASE



`PHASE=4C` is the **runtime architecture milestone**. Not the engineering phase label. Unchanged in Phase 7D.



## Draft release tag side effect



Creating a draft GitHub Release via `release-fork.yml` may create Git ref `v3.2.0-oss.1` at the release commit. Expected.



Inherited upstream `release.yml` remains inert on xStoikk via `github.repository == 'beakthoven/TrickyStoreOSS'` guard — do not remove.
