# Tricky Store OSS v3.2.0-oss.1

First fork release from xStoikk, based on TrickyStoreOSS upstream by beakthoven.

## Highlights

- Fork release identity and update feed are now separated from upstream release ordering.
- Fork versionCode uses the reserved epoch strategy `100000 + commitCount`.
- Plain AUTO routing remains isolated from synthetic and hybrid modes.
- Release preparation validates the complete release build before generating metadata.
- Release ZIP packaging uses normalized timestamps and reproducible entry ordering.
- GitHub draft releases are built from the exact protected-main commit.
- Release artifacts include GitHub build-provenance attestation.

## Release identity

- Version: `v3.2.0-oss.1`
- versionCode: `100198`
- Source commit: `4f3e3e21ae1057de0acce6d1485138d2d356513b`
- Release ZIP: `Tricky-Store-OSS-v3.2.0-oss.1-198-4f3e3e2-Release.zip`
- Release ZIP SHA256: `9f0f9f2e70bc6409a794054f39452ea0e3e4c26cbae6bf187c70c99fc49386c7`
- classes.dex SHA256: `d8e0bf460f138a53c863c14c8a9ffa3012c377f93c209ec90260c1a10f296500`

## Attribution

Tricky Store OSS is based on the upstream TrickyStoreOSS project by beakthoven.
