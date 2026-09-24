# Log privacy conventions

Tricky Store OSS diagnostics are designed for operator debugging on rooted devices. They intentionally avoid leaking high-sensitivity material where practical.

## Hashed / redacted

| Data | Convention |
|------|------------|
| Key alias | `alias_hash=` in `PASSTHROUGH_*`, `CERT_STATE_CLEAR` via `PassthroughKeyRegistry.aliasHash()` |
| Attestation challenge | Not logged |
| Certificate PEM/DER | Not logged |
| Private key material | Never logged |
| Full IMEI / serial | Not logged in TS_DIAG |

## Accepted diagnostic identifiers

| Data | Usage |
|------|-------|
| UID | `uid=` in routing, passthrough, cert clear, grant authority |
| PID | Daemon / probe context only |
| Package names | `MODE_ROUTE pkgs=` when PackageManager cache populated — useful but may be verbose; `MODE_SCOPE` uses count only |
| Keybox path | `TEE_DECISION path=` points to tee_status file path, not keybox contents |

## Stable event IDs vs descriptions

Legacy identifiers remain for log parser compatibility:

- `needHack`, `needGenerate`, `forceForge` — internal names reflecting historical "leaf hack" / forge terminology; semantics documented in [diagnostics.md](diagnostics.md)
- `skip leaf hack` — Android log message string in Keystore2Interceptor; behavior is explicit hybrid path skip

Surrounding comments and docs use current terminology (explicit hybrid, explicit synthetic).

## Operator guidance

- Capture logcat with `adb logcat -s TrickyStoreOSS:*` on debug builds.
- Do not share logs publicly without reviewing `pkgs=` lines if package lists are sensitive.
- Diagnostics **do not** replace independent attestation verification (Key Attestation app, server-side cert validation).
