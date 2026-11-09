# Backend build facts

Use release tag `tutanota-release-366.261105.0`, commit
`332e57461cdc4fe4a4ad9bdc8555f56df02931c0`. Never use master as the protocol pin.
`upstream.json` records the source hashes. Apply overlays to exported source only.

The native build requires Rust 1.97.1, Python 3, Node.js, and Android NDK r29.
The vector generator uses Node 26 and npm 10. Install release packages with
`npm ci --ignore-scripts` in a separate checkout. Never run install scripts.
The JVM toolchain is Java 21. Keep the SDK location in ignored local.properties.

Run these commands from the repository root:

1. `TUTA_REFERENCE="$TUTA_REFERENCE" tools/backend/build-sdk.sh`
2. `CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0 tools/backend/test-read-overlay.sh`
3. `./gradlew testDebugUnitTest :core:domain:test :core:html:test assembleDebug --console=plain --max-workers=2`
4. `TUTA_RELEASE="$TUTA_RELEASE" node tools/backend/vectors/run.mjs`

`TUTA_REFERENCE` must contain the pinned release tag. The build exports its
commit into `core/sdk/build/native/source`. It writes ignored native libraries,
Kotlin bindings, and checksums into `core/sdk/build/native/artifacts`.
`TUTA_RELEASE` must be a separate clean checkout at the pinned commit.
See `vectors/README.md` for vector setup and test coverage.

These commands do not test a live Tuta account. Do not make live Tuta requests. Do not change
app, feature, or design-system modules during backend-only work.
