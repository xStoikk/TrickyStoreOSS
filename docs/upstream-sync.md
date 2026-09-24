# Upstream sync policy

Tricky Store OSS is maintained as a fork with intentional architecture divergence.

| Remote | Repository | Role |
|--------|------------|------|
| **origin** | `xStoikk/TrickyStoreOSS` | Canonical fork — development, CI, releases |
| **upstream** | `beakthoven/TrickyStoreOSS` | Upstream source — **fetch only** |

## Safe sync workflow

```bash
git fetch upstream
git log --oneline main..upstream/main   # inspect upstream-only commits
git log --oneline upstream/main..main   # inspect fork-only commits
```

**Never** blindly reset `main` to `upstream/main`. The fork may contain accepted phases (6J–7A+) that upstream does not yet include.

### Integration branch pattern

```bash
git fetch upstream
git switch -c integrate/upstream-$(date +%Y%m%d) main
git merge upstream/main   # or cherry-pick selected commits
# resolve conflicts with fork architecture docs as authority
pwsh scripts/validate-release.ps1
```

Merge to `main` only after:

1. Manual review of attestation/routing/TEE diffs
2. Full release validation (142+ unit tests, verifiers, lint)
3. Explicit decision that upstream changes do not regress fork invariants

## Intentional divergence

The fork may retain:

- Explicit AUTO / `!` / `?` policy (Phase 6K)
- TEE probe lifecycle fixes
- Packaging verifier contract
- Documentation and CI gates

Upstream merges are **selective**, not automatic.

## Remotes setup (reference)

```bash
git remote add upstream https://github.com/beakthoven/TrickyStoreOSS.git
git remote -v
```

Do not force-push `main` on origin after upstream sync without maintainer review.
