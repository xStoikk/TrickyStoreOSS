# Upgrading from older builds

Guidance for operators moving to Phase 6K+ routing from builds that allowed silent AUTO synthetic fallback or served stale certificate caches aggressively.

## What changed (behavioral)

| Older behavior | Current behavior |
|----------------|------------------|
| BROKEN TEE + AUTO could silently `generate` software attestation | AUTO → real keystore or real error only |
| Untracked AUTO `getKeyEntry` could re-sign via CertificateHack | Untracked real response preserved unchanged |
| Plain AUTO after prior `!`/`?` could serve stale generated/patch cache | Plain AUTO invalidates stale owners on `getKeyEntry` |
| Grant shortcut could serve owner synthetic to plain AUTO grantee | Withheld; real keystore grant read |

**No global migration wipe** of `/data/adb/tricky_store/keys/` runs on upgrade. Persistence files remain on disk until explicitly cleared or invalidated by policy.

## Stale generated owner on disk + current plain AUTO

Scenario: Previously used `package!`, generated keys persisted, then target changed to plain AUTO.

On next plain AUTO `getKeyEntry`:

1. `auto-reject-historical-generated-owner` clears in-memory cache and persistence for that alias.
2. Call forwards to real keystore.
3. Trust: `REAL_KEYSTORE_UNMODIFIED` / `auto-current-mode-real-response`.

**Action required:** None if plain AUTO is intended. Switch back to `!` if software synthetic is still desired.

## Stale patched response + current plain AUTO

Scenario: Previously used `package?`, patched cache exists, then target changed to plain AUTO.

On plain AUTO `getKeyEntry`:

1. `auto-reject-historical-patched-owner` clears patched cache.
2. Forwards to real keystore.

## Old BROKEN AUTO synthetic fallback

If you relied on automatic software attestation when TEE probe reported BROKEN:

- **Must** add explicit `!` to `target.txt` for that package (or shared UID).
- Expect `trustClass=SOFTWARE_SYNTHETIC` only with explicit opt-in.

## Shared UID configuration changes

Review all packages sharing a UID. Mixed AUTO + `!` means the UID is never plain AUTO even if one package line looks like AUTO only.

## Grant relationships

If app A (`!`) granted a synthetic key to app B (AUTO):

- Plain AUTO grantee B no longer receives TrickyStore grant cache shortcut.
- Real keystore grant path applies.

## Recommended upgrade checklist

1. Review `target.txt` suffixes — add `!` only where software synthetic is intentional.
2. After changing `!` → plain AUTO, trigger one `getKeyEntry` (or use Key Attestation app / `keystore_cli_v2 export`) to flush stale owners.
3. Inspect `TS_DIAG` for `CERT_STATE_CLEAR` and `MODE_SCOPE` after changes.
4. Do **not** delete `keys/` wholesale unless you intend to drop all historical synthetic material.

## Rollback

Flash prior module ZIP and reboot. Persisted keys and config under `/data/adb/tricky_store/` are not removed by flashing a different module version unless the installer or you delete them.
