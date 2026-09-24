# Release notes (draft)

Draft user-visible summary for the next published release after legacy silent-synthetic AUTO behavior. **Not a version bump commitment.**

## Highlights

### Real TEE passthrough preserved

Plain AUTO forwards attestation to the device keystore. Successful real-TEE key generation is tracked for coherent later reads. Untracked `getKeyEntry` responses are returned unchanged.

### Android 16 TEE probe lifecycle

Probe bootstrap and retry policy handle deferred RKP/registration failures without prematurely classifying TEE as BROKEN. Transient startup errors retry within bounded windows.

### Cache ownership isolation

Certificate response caches are keyed by `(uid, alias)`. Ownership transitions (`generate` ↔ passthrough ↔ patch) clear stale state deterministically. `updateSubcomponent` invalidates cached cert material appropriately.

### AUTO ATTEST_KEY coherence

AUTO with attestation-key purpose or attestation key descriptor uses passthrough when TEE is usable — request shape alone does not force software generation.

### Untracked getKeyEntry preservation (Phase 6J)

AUTO callers reading keys not tracked by TrickyStore receive the real keystore certificate bytes without silent CertificateHack re-signing.

### Explicit synthetic / hybrid policy (Phase 6K)

- **AUTO:** Real keystore or real error — including when TEE probe reports BROKEN. No silent software attestation.
- **`!`:** Explicit software-synthetic mode (operator opt-in).
- **`?`:** Explicit hybrid re-sign mode.
- Plain AUTO rejects stale generated or patched owners on `getKeyEntry`.
- Current `target.txt` mode wins over historical cache.

### Shared-UID and grant-domain hardening

- Mode configuration aggregates at UID scope (Binder limitation).
- Mixed AUTO + explicit suffix on shared UID elevates entire UID.
- Plain AUTO grantees no longer receive TrickyStore synthetic cache via grant shortcut.

### Packaging and build metadata

- Release ZIP verifier enforces module layout (19 files / 9 dirs / 28 entries).
- `classes.dex`-only Release artifact; debug APK separate.
- `BUILD_ID` embeds git SHA for install verification.

## Operator actions

If you previously relied on automatic software attestation when TEE was broken, add explicit `!` to `target.txt` for that package.

Review shared-UID package lists before mixing AUTO and explicit suffixes.

## Not included / not claimed

- Does **not** guarantee Play Integrity MEETS_DEVICE_INTEGRITY or STRONG verdicts.
- Does **not** silently upgrade AUTO to hardware security levels in synthetic modes.
- Does **not** modify GMS or Play Store.

## Documentation

See `docs/attestation-routing.md`, `docs/trust-model.md`, `docs/target.txt.md`, `docs/upgrading.md`, `docs/diagnostics.md`.
