# Trust model and boundaries

Phase 6H+ conclusion: **certificate contents** and **trust anchor / provenance labels** are separate concepts. TrickyStore diagnostics describe **routing ownership**, not independent verification of Google hardware attestation or Play Integrity verdicts.

## What TrickyStore knows vs what it proves

| Label | TrickyStore knows | Does NOT prove |
|-------|-------------------|----------------|
| `HARDWARE_PASSTHROUGH` | This alias was promoted via a successful real-TEE `generateKey` passthrough path and is tracked. | Google/RKP chain validity, STRONG/DEVICE verdict, bootloader state |
| `REAL_KEYSTORE_UNMODIFIED` | Binder returned a real keystore reply without TrickyStore cert mutation on this path. | That the chain is hardware-backed, unrevoked, or integrity-passing |
| `SOFTWARE_SYNTHETIC` | Software-generated attestation material (explicit `!` or generated owner). | Hardware security level or TEE backing |
| `HYBRID_RE_SIGNED` | Certificate chain was locally re-signed/replaced (explicit `?` / patch owner). | Unmodified hardware attestation |
| `UNINTERCEPTED` | No attestation routing applied. | — |

**Do not** interpret `REAL_KEYSTORE_UNMODIFIED` as “verified hardware attestation.” It means **provenance: real keystore response, unchanged by TrickyStore on that hop.**

## REAL TEE passthrough

When AUTO routing selects `passthrough-real-tee` and the real keystore succeeds:

- Key generation and attestation challenges are handled by the device TEE stack.
- TrickyStore may track the alias in `PassthroughKeyRegistry` for coherent later `getKeyEntry` behavior.
- Diagnostic: `trustClass=HARDWARE_PASSTHROUGH` (tracked) or `REAL_KEYSTORE_UNMODIFIED` (untracked getKeyEntry).

Preserves **real attestation provenance** relative to TrickyStore — not a claim about external validators.

## SOFTWARE_SYNTHETIC

Explicit `!` mode or serving a generated owner:

- Key material and chain are produced locally from keybox configuration.
- Intended for compatibility scenarios where the operator **explicitly** opts in.
- **Not equivalent** to genuine hardware attestation and must not be described as such.

## HYBRID_RE_SIGNED

Explicit `?` mode:

- May retain hardware key / SPKI from a real TEE `generateKey`.
- Certificate chain is re-signed or replaced (CertificateHack leaf path).
- Security level in certificates may not match what a naive reader assumes from key origin alone.

## AUTO trust invariant (Phase 6K)

A scope that is **genuinely plain AUTO** (UID aggregate with no `!`/`?`) must never receive `SOFTWARE_SYNTHETIC` or `HYBRID_RE_SIGNED` solely because of:

- BROKEN TEE state
- Untracked alias heuristics
- Stale generated or patched owners
- Grant-domain cache shortcuts

AUTO may still receive real keystore errors, or unchanged real success responses.

## Shared UID boundary

Binder exposes **UID**, not package name. Trust and mode decisions aggregate across all targeted packages sharing a UID. Package-level isolation is **impossible** at this layer.

## Grant boundary

Grantee UID determines whether plain AUTO withhold applies. Owner UID keys the synthetic/patch cache. Certificate material served through grants reflects **owner key provenance** when explicitly allowed — but plain AUTO grantees are blocked from TrickyStore synthetic shortcut serves.

## Privacy

See [log privacy conventions](log-privacy.md).
