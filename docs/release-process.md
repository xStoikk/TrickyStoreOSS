# Release process (design)

Phase 7B defines mechanics; **automatic publishing is not enabled** on the xStoikk fork until explicitly authorized.

## Current state

| Component | Behavior on `xStoikk/TrickyStoreOSS` |
|-----------|----------------------------------------|
| `Build` workflow | Push/PR to `main` — full acceptance gate, uploads CI artifacts |
| `Release` workflow | **Inert** — inherited from upstream but job guard skips publishing |
| `changelogs.yml` | **Inert** — no `update.json` / `changelog.md` commits on fork |
| Local cut | `scripts/validate-release.ps1` |

**Upstream release automation is intentionally inert on the xStoikk fork until the fork release workflow is explicitly enabled.**

Inherited workflows remain in the repository for upstream compatibility and future fork adaptation. Jobs run only when:

```yaml
github.repository == 'beakthoven/TrickyStoreOSS'
```

On the fork:

- Pushing a `v*` tag **does not** create a GitHub Release
- Editing a release **does not** trigger metadata commits to `main` / `changelog`
- Later fork release work must deliberately replace or remove this guard

Do **not** push `v*` tags on the fork expecting safe no-ops without these guards (now present as of Phase 7B-FINAL).

## CI trigger policy (`Build` workflow)

`paths-ignore` on push/PR to `main`:

```yaml
- '**.md'
- 'update.json'
```

| Change | Build runs? |
|--------|-------------|
| App source / tests | Yes |
| Gradle / build files | Yes |
| `scripts/**` | Yes |
| `.github/workflows/**` | Yes — workflows are **not** ignored |
| Docs-only (`*.md`) | No (may skip) |
| `update.json` only | No (may skip) |

**Note:** GitHub `paths-ignore` supports exclusions only. Re-inclusion via `!.github/workflows/**` under `paths-ignore` is not reliable. The fork uses a minimal ignore list so workflow edits always run CI.

## Acceptance before any release

See [release-validation.md](release-validation.md). CI and local script enforce the same Gradle gates.

Run `pwsh scripts/validate-release.ps1` **after** committing Phase work (requires clean tree).

## Future manual release workflow (proposed)

**Trigger:** `workflow_dispatch` only (not tag push auto-publish).

**Inputs:** version, optional versionCode override, prerelease flag.

**Steps (future):** validate → build exact commit → hash artifacts → draft GitHub release → attach Release ZIP → deliberate `update.json` update.

Do **not** implement fork publishing in Phase 7B.

## Version metadata

| Field | Source |
|-------|--------|
| `verName` | `app/build.gradle.kts` — manual authorization to change |
| `versionCode` / ZIP count segment | `git rev-list HEAD --count` — auto-increments per commit |
| `TeeBuildInfo.GIT` | short SHA (+ `-dirty` if tree dirty at build time) |

After Phase 7B commit: expect versionCode **189** (was **188** pre-commit). This is expected — not a manual version bump. Keep `v3.1.6-auto-tee-passthrough` until authorized.

## Tag policy

| Tag pattern | Purpose |
|-------------|---------|
| `phase*-accepted` | Engineering checkpoint tags |
| `v*` | Reserved future public-release namespace; inherited publishing automation is **fork-disabled** on xStoikk |

Phase tags ≠ public release tags. Do not rename historical phase tags.

## Branch policy

| Branch | Role |
|--------|------|
| `main` | Accepted development line |
| `chore/*`, `fix/*`, `research/*` | Phase and fix work |
| PRs | Merge to `main` after green `Build` workflow |

## update.json

Currently references upstream release URL (`beakthoven/TrickyStoreOSS`). Fork releases must update version, versionCode, zipUrl, and changelog URL deliberately — not via inherited changelog automation while guards are active.
