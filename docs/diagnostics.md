# Diagnostic event catalog

All lines are prefixed with `TS_DIAG` in logcat tag `TrickyStoreOSS`.

Diagnostics describe **routing decisions and provenance**, not external attestation verdicts.

## Mode selection

### `MODE_INPUT`

Fields: `attestationKeyDescriptor`, `explicitSynthetic`, `hasAttestKeyPurpose`, optional `teeState`

| Field | Meaning |
|-------|---------|
| `attestationKeyDescriptor=set` | Request references an attestation key descriptor |
| `explicitSynthetic=true` | UID has explicit `!` (`isExplicitGenerate`) |
| `teeState=WORKING\|BROKEN\|UNKNOWN` | Snapshot for routing diagnostics |

**Proves:** Input flags seen by generateKey routing.  
**Does not prove:** Final certificate trust or Play Integrity outcome.

### `MODE_SCOPE`

Fields: `uid`, `scope=shared-uid`, `packages=N`, `mixedModes=true`

Emitted once per UID when multiple packages share a UID and targeted modes mix (e.g. AUTO + `!`).

**Proves:** Aggregated UID scope is ambiguous at package level.  
**Does not prove:** Which package initiated the call.

### `MODE_ROUTE`

Fields: `uid`, `pkgs`, `alias`, `needHack`, `needGenerate`, attestation flags, `forceForge`, `selected`, optional `trustClass`, `trustClassReason`

| Field | Meaning |
|-------|---------|
| `needHack=true` | Leaf/hybrid path eligible (AUTO or `?`) |
| `needGenerate=true` | Explicit generate path eligible (`!`; AUTO never after 6K) |
| `forceForge=true` | Explicit synthetic generation selected |
| `selected=passthrough-real-tee\|generate\|leaf-forward-*` | Top-level route |
| `trustClass=*` | Provenance label (see [trust model](trust-model.md)) |

**Proves:** Routing decision for this generateKey call.  
**Does not prove:** Hardware security level in resulting certificates.

`pkgs=` may list package names when cached from PackageManager — treat as diagnostic only.

## TEE probe

### `TEE_DECISION`

Fields: `teeBroken=true|false`, or `deferred state=UNKNOWN`

**Proves:** Persisted TEE capability snapshot used for AUTO predicates.  
**Does not prove:** Device will pass hardware attestation checks elsewhere.

Related: `TEE_STATE`, `TEE_PROBE_*`, `TEE_STATUS`, `TEE_TRANSIENT`, `TEE_PERMANENT`.

## Passthrough delivery

### `PASSTHROUGH_RESULT`

Fields: `uid`, `result=success|failure`, `reply_unchanged=true|false`, optional `reason`

| `reply_unchanged=true` | Real keystore bytes returned without TrickyStore cert mutation |
| `reason=real-keystore-error` | Forwarded failure from real keystore |

**Proves:** Binder-level passthrough outcome.  
**Does not prove:** Attestation chain validity.

### `PASSTHROUGH_TRACK`

Fields: `event=promote|remove`, `uid`, `alias_hash`

Uses **hashed alias**, not raw alias string.

### `PASSTHROUGH_PROVENANCE`

Fields: kind (`real-keystore-untracked`, etc.), `uid`, `alias_hash`

Distinguishes tracked vs untracked real getKeyEntry delivery.

## Certificate path

### `CERT_PATH`

Fields: `action`, `reason`, optional `trustClass`, `trustClassReason`

Post-hook or pre-hook certificate handling decision.

### `CERT_STATE_CLEAR`

Fields: `uid`, `alias_hash`, `reason`

Examples: `auto-reject-historical-generated-owner`, `auto-reject-historical-patched-owner`, `transition-to-passthrough`.

**Proves:** Cache ownership cleared.  
**Does not prove:** Real keystore key deleted.

### `CERT_MODE_AUTHORITY`

Fields: `authority`, `callerUid`, `ownerUid`

Example: `authority=grant-withhold-plain-auto` — plain AUTO grantee blocked from grant cache shortcut.

**Proves:** Identity-scope policy applied on grant path.  
**Does not prove:** Grantee received real TEE material (check subsequent passthrough / keystore outcome).

## Trust class quick reference

| trustClass | Trusted interpretation | Does NOT prove |
|------------|------------------------|----------------|
| `HARDWARE_PASSTHROUGH` | Known real-TEE tracked route | Google hardware attestation validity |
| `REAL_KEYSTORE_UNMODIFIED` | Unmodified real keystore reply on this hop | Verified STRONG/DEVICE / RKP |
| `SOFTWARE_SYNTHETIC` | Explicit or cached software synthetic | Hardware backing |
| `HYBRID_RE_SIGNED` | Locally re-signed/replaced chain | Unmodified hardware chain |
| `UNINTERCEPTED` | No attestation routing | — |

## Build / daemon

- `BUILD_ID` — embeds version, git short SHA, phase metadata
- `DAEMON_START`, `INTERCEPTOR_*` — injection lifecycle

See [log privacy](log-privacy.md).
