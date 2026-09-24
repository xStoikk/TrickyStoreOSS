# Release process (design)

Canonical fork: **xStoikk/TrickyStoreOSS**. Automatic publishing is **not enabled** until explicitly authorized.

## Current automation state

| Component | xStoikk fork behavior |
|-----------|----------------------|
| `Build` workflow | Full acceptance gate; CI artifacts on green builds |
| `Release` workflow | **Inert** — `github.repository == 'beakthoven/TrickyStoreOSS'` guard |
| `changelogs.yml` | **Inert** — same guard |
| Local validation | `pwsh scripts/validate-release.ps1` |
| Release prep (dry-run) | `pwsh scripts/prepare-release.ps1` (no `-Tag` until product version authorized) |

**Upstream release automation is intentionally inert on the xStoikk fork until fork release workflow is explicitly enabled.**

## Protected `main` ruleset (active)

Ruleset: **Protect main** (default branch)

| Rule | Setting |
|------|---------|
| Restrict deletions | On |
| Block force pushes | On |
| Require linear history | On |
| Require status check **`build`** | On |
| Require branches up to date | Off |
| Require signed commits | Off |
| Require pull request | Off |
| Bypass actors | None |

### Exact-SHA merge flow

Because linear history is required and PR-merge commits are not used:

1. Work on feature branch
2. Open PR (optional but recommended for review) — **Build must pass** on PR head
3. Fast-forward validated commit SHA onto `main` (not a merge commit)
4. Push updated `main`

Direct pushes to `main` remain possible today (PR not required), but force-push and deletion are blocked.

## CI trigger policy (`Build` workflow)

| Event | paths-ignore | Build runs when |
|-------|--------------|-----------------|
| **push** → `main` | `**.md`, `update.json` | Any non-doc, non-update.json change |
| **pull_request** → `main` | *(none)* | **Always** — required for branch protection |
| **workflow_dispatch** | — | Always |

Docs-only or `update.json`-only **pushes** may skip CI. **All PRs** run Build regardless of changed paths.

## Acceptance before release

See [release-validation.md](release-validation.md).

1. Clean tree on **protected `main`** (or exact SHA fast-forwarded to main)
2. `pwsh scripts/validate-release.ps1`
3. `pwsh scripts/prepare-release.ps1` — post-7C acceptance **without `-Tag`**
4. After product version bump: `pwsh scripts/prepare-release.ps1 -Tag <tag>` where **tag == product version**

Output: `out/release-prep/` (cleared each run; stale `update.json.next` removed when `-Tag` omitted).

### Tag / product coherence

`-Tag` must match packaged product version from Release ZIP `module.prop`. Mismatch fails nonzero with no override.

### First public fork release

See [release-identity.md](release-identity.md) steps A–H. First public release ships with xStoikk `updateJson` and fork `update.json` already describing that release before publish.

## Version metadata

| Field | Build input | Release prep truth (after validate) |
|-------|-------------|-----------------------------------|
| Product version | `gradle.properties` → `trickyStoreVersionName` | Packaged `module.prop` in Release ZIP |
| versionCode / ZIP count segment | `git rev-list HEAD --count` | Packaged `module.prop` |
| TeeBuildInfo.GIT | short SHA (+ `-dirty` if dirty at build) | Git HEAD at prep time |
| TeeBuildInfo.PHASE | `teeBuildPhase` in `app/build.gradle.kts` | Generated `TeeBuildInfo.kt` `PHASE` |

Changing `trickyStoreVersionName` requires maintainer authorization.

## Tag policy

| Pattern | Purpose |
|---------|---------|
| `phase*-accepted` | Engineering checkpoint |
| `v*` | Future public release namespace; **fork publishing disabled** via upstream guards |

## update.json ownership (future)

See [release-identity.md](release-identity.md) steps A–H. Summary:

1. `prepare-release.ps1 -Tag` produces **candidate** `out/release-prep/update.json.next`
2. Draft GitHub Release + upload validated ZIP
3. Commit tracked `update.json` via protected `main` (green Build) **before** publishing release
4. Verify raw fork feed, then publish draft release
5. No automation may push to `main` while bypass actors are absent

Tracked `update.json` today still references **upstream** — intentional until first public fork cutover.

## update.json / versionCode risk (installed fork build)

Installed fork module.prop points at upstream `updateJson`. Tracked upstream feed: `v3.1.0`, versionCode **172**.

Fork builds use versionCode = **git commit count** (191+ on current main).

| Question | Assessment |
|----------|------------|
| Upstream versionCode older than fork? | **Yes** today (172 ≪ 191) |
| Upstream versionCode could exceed fork later? | **Yes** if upstream history diverges and uses its own count |
| Fork install offered upstream module as update? | **Possible** — depends on module manager comparing remote versionCode to installed (KernelSU / manager behavior — **verify externally**) |
| Git count safe forever across fork+upstream? | **Risk** — independent histories can invert ordering; consider epoch offset before public feed cutover |

**Recommendation (not final):** keep git count for development; evaluate **`100000 + commitCount`** before enabling xStoikk update feed. Public releases only from protected `main`.

## Future fork release workflow (design only)

File: `.github/workflows/release-fork.yml` *(not implemented in Phase 7C)*

**Trigger:** `workflow_dispatch`
**Inputs:** `tag`, `prerelease`, `draft`

Flow:

1. Checkout selected ref
2. Full six-gate Gradle build + artifact discovery
3. Run prepare-release metadata logic
4. Verify tag/version compatibility
5. Create **draft** GitHub Release; upload Release ZIP
6. Optional provenance attestation
7. **Stop** — no `update.json` mutation
8. Maintainer verifies draft; separate metadata PR after publish

Do not add `contents: write` to ordinary Build workflow.

## Related docs

- [release-identity.md](release-identity.md)
- [release-validation.md](release-validation.md)
- [upstream-sync.md](upstream-sync.md)
