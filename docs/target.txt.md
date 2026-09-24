# target.txt semantics

Configuration path: `/data/adb/tricky_store/target.txt`

Reload: automatic on file change (no reboot required).

## Line format

| Line | Mode | Meaning |
|------|------|---------|
| `package.name` | **AUTO** | Real keystore forward; no silent synthetic/hybrid |
| `package.name!` | **GENERATE** | Explicit software-synthetic (`SOFTWARE_SYNTHETIC`) |
| `package.name?` | **LEAF_HACK** | Explicit hybrid re-sign (`HYBRID_RE_SIGNED`) |

Lines starting with `#` are comments. Blank lines are ignored.

Packages **not listed** are not interception targets (calls may still hit generic forged-key serve paths if historical state exists — see [upgrading](upgrading.md)).

## Examples

```
# Plain AUTO — real TEE / real keystore
io.github.vvb2060.keyattestation

# Explicit hybrid leaf re-sign
com.example.attest.app?

# Explicit software synthetic
com.example.legacy.app!
```

## Shared UID aggregation

Android may assign **multiple packages to one UID**. TrickyStore resolves `getPackagesForUid(callingUid)` and **aggregates** modes:

| UID packages (targeted) | Effective UID behavior |
|-------------------------|------------------------|
| AUTO only | Genuinely **plain AUTO** |
| `!` only | Explicit generate |
| `?` only | Explicit leaf / hybrid |
| AUTO + `!` | **Not** plain AUTO; `needGenerate=true`; explicit-generate capable |
| AUTO + `?` | **Not** plain AUTO; explicit-leaf capable |
| `!` + `?` | Both explicit flags; generate pre-hook wins for stale generated owners |
| AUTO + `!` + `?` | Fully explicit UID; no plain AUTO invalidation |

**Warning:** Package-level isolation is **impossible** where Binder exposes only UID. Putting one app in AUTO and a shared-UID sibling in `!` elevates the entire UID.

Diagnostic when mixed modes detected on a multi-package UID:

```
MODE_SCOPE uid=… scope=shared-uid packages=N mixedModes=true
```

(Package names are not logged — count only.)

## Relationship to TEE probe

| TEE state | AUTO | `!` | `?` |
|-----------|------|-----|-----|
| WORKING | Real passthrough | Software synthetic | Hybrid re-sign |
| UNKNOWN | Real forward (no silent synthetic) | Software synthetic | Hybrid |
| BROKEN | Real forward or real error (**no** silent synthetic) | Software synthetic | Hybrid |

## See also

- [Attestation routing](attestation-routing.md)
- [Trust model](trust-model.md)
- [Upgrading](upgrading.md)
- README [Configuration](../README.md#configuration)
