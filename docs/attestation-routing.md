# Attestation routing contract

Accepted baseline: Phase 6K (`1d4884d`). This document describes **current behavior**, not aspirational design.

Tricky Store OSS intercepts Keystore2 attestation-related calls for packages listed in `target.txt`. The binder layer identifies callers by **UID only**; routing aggregates all packages sharing that UID. See [target.txt semantics](target.txt.md) and [trust model](trust-model.md).

## Trust / provenance classes

| Class | Meaning |
|-------|---------|
| `HARDWARE_PASSTHROUGH` | Known real-TEE route ownership (tracked passthrough key). |
| `REAL_KEYSTORE_UNMODIFIED` | Real keystore binder reply delivered unchanged. **Does not** prove verified Google/RKP hardware attestation. |
| `SOFTWARE_SYNTHETIC` | Locally produced software attestation chain (explicit `!` / generated owner). |
| `HYBRID_RE_SIGNED` | May retain hardware key/SPKI; certificate chain re-signed or replaced (explicit `?`). |
| `UNINTERCEPTED` | Call not handled by attestation routing (skip / non-target). |

---

## AUTO (no suffix)

**User syntax:** `com.example.app`

**Route:** `passthrough-real-tee` on `generateKey`; real keystore forward on `getKeyEntry` when no stale synthetic owner applies.

**Trust class:**
- Working TEE + tracked real key → `HARDWARE_PASSTHROUGH`
- Untracked real `getKeyEntry` → `REAL_KEYSTORE_UNMODIFIED`
- Plain AUTO current-mode passthrough → `REAL_KEYSTORE_UNMODIFIED` (`auto-current-mode-real-response`)
- BROKEN TEE + AUTO → `REAL_KEYSTORE_UNMODIFIED` (`auto-broken-no-synthetic-fallback`) — **not** silent synthetic

**Real vs synthetic key:** Real TEE / real keystore key material. No TrickyStore software key generation unless historical owner is cleared first.

**Certificate chain source:** Device TEE / Android Keystore as returned by the real keystore.

**TEE failure (BROKEN):** Forward to real keystore; propagate genuine success or error. **No** automatic `SOFTWARE_SYNTHETIC` fallback.

**TEE unknown (UNKNOWN):** Same as AUTO with usable intercept path — forward, do not silently synthesize.

**Stale cache:** On plain AUTO `getKeyEntry`, historical generated or patched owners are rejected (`auto-reject-historical-*`) and the call forwards to the real keystore.

**getKeyEntry:**
- Tracked passthrough → return reply unchanged (`PASSTHROUGH_UNMODIFIED`)
- Plain AUTO + no historical owner → forward; post-hook preserves untracked real responses (Phase 6J)
- Plain AUTO + historical generated/patch → invalidate, then forward

**Grant domain:** Plain AUTO grantee must **not** receive TrickyStore synthetic/patch cache via grant shortcut; falls through to real keystore grant read (`grant-withhold-plain-auto`).

**Shared UID:** Plain AUTO only when **no** package on the UID has `!` or `?`. Any explicit suffix on any shared package elevates the whole UID.

---

## Explicit synthetic (`!`)

**User syntax:** `com.example.app!`

**Route:** `generate` (`ROUTE_GENERATE`) when `needGenerate` is true for the calling UID.

**Trust class:** `SOFTWARE_SYNTHETIC` (`explicit-generate`)

**Real vs synthetic key:** TrickyStore-generated software keypair; may be persisted under `/data/adb/tricky_store/keys/`.

**Certificate chain source:** Keybox / `CertificateGen` software chain — **not** genuine hardware attestation.

**TEE failure:** Still generates when explicitly requested (`!`). BROKEN TEE does not block explicit `!`.

**Stale cache:** Generated owner served from cache on `getKeyEntry` while UID remains explicit-generate capable.

**getKeyEntry:** Serves cached generated response when present; grant domain may serve owner cache to non-plain-AUTO grantees.

**Grant domain:** Owner `!` synthetic may be served to grantees that are **not** plain AUTO.

**Shared UID:** If any package is `!`, UID is explicit-generate capable (`isExplicitGenerate=true`, `isPlainAuto=false`).

---

## Explicit hybrid / re-sign (`?`)

**User syntax:** `com.example.app?`

**Route:** `leaf-forward-attestation` on `generateKey`; leaf patch path on `getKeyEntry` when applicable.

**Trust class:** `HYBRID_RE_SIGNED` (`explicit-leaf-forward`, `cached-patch-owner`, or `PATCH_LEAF`)

**Real vs synthetic key:** Often a real TEE-generated key with **modified** certificate chain (CertificateHack leaf re-sign).

**Certificate chain source:** Hybrid — hardware key material may remain; attestation chain replaced or re-signed locally.

**TEE failure:** Explicit `?` still uses hybrid path when selected; not upgraded to plain AUTO passthrough.

**Stale cache:** Patched responses served while UID remains explicit-leaf capable.

**getKeyEntry:** May serve cached patch, apply `PATCH_LEAF`, or forward depending on passthrough tracking and post-policy.

**Grant domain:** Patched/generated owner cache keyed by owner UID; grantee mode governs withhold (plain AUTO grantee → no TrickyStore grant cache serve).

**Shared UID:** If any package is `?`, UID is explicit-leaf capable (`isExplicitLeafHack=true`, `isPlainAuto=false`).

---

## Keystore domains (reference)

| Domain | Routing notes |
|--------|----------------|
| APP | Primary alias + UID-scoped config and caches |
| SELINUX | Forwarded; not primary attestation routing surface |
| GRANT | Grantee UID for config; owner `Key(uid,alias)` for cache; plain AUTO withhold on synthetic grant serve |
| KEY_ID / BLOB | Forwarded; certificate routing focuses on APP + GRANT |

---

## Related documents

- [Trust model](trust-model.md)
- [target.txt semantics](target.txt.md)
- [Upgrading](upgrading.md)
- [Diagnostics catalog](diagnostics.md)
- [Release validation gate](release-validation.md)
