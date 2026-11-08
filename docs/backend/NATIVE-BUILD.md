# Native SDK build

Reference: Tuta `adccedea76c7049c78c9404332afda50f84bb05a`.
UniFFI: `13a1c559cb3708eeca40dcf95dc8b3ccccf3b88c`, from the reference Cargo workspace.

1. Use the existing Rust 1.97.1 toolchain and JDK 21.
2. Install `clang`, `lld`, `llvm-18`, `curl`, and `unzip` with apt.
3. Run `tools/backend/build-sdk.sh` from this worktree.
4. Run `./gradlew :core:sdk:assembleDebug :core:sdk:testDebugUnitTest`.

The script exports tracked upstream source into `core/sdk/build/native/source`. It never edits the reference checkout. Set `TUTA_REFERENCE` to another local checkout if needed. It downloads NDK r29 with curl. It checks the SHA-1 published in Google's repository metadata, then records SHA-256 checksums of the output. All Rust packages use the pinned Cargo.lock.

Only the NDK sysroot and target runtime archives are extracted. ARM-native clang 18 and lld build Android API 26 libraries for arm64-v8a and x86_64. No Google x86 host executable runs. ELF load segments use 16 KiB alignment. A host library supports offline JVM tests of UniFFI calls. This host library is not packaged in the Android AAR.

Output is in `core/sdk/build/native/artifacts`. It includes JNI libraries, internal Kotlin bindings, license, tool versions, reference commit, and `SHA256SUMS`. Gradle verifies that manifest before building. Native binaries and bindings stay in ignored build output. A clean checkout must run the script first. Reproducible here means pinned inputs and repeatable commands; byte-identical output across host toolchain versions is not claimed.

The SDK adapter keeps generated records internal. JNA 5.18.0 is required by upstream UniFFI's Kotlin generator. Coroutines support its async callbacks. No extra UniFFI export or upstream patch is needed for the current transport and Argon2id adapter.

The pinned Rust dependency graph has known advisories. See `dependency-advisories.json` for exact packages, OSV IDs, aliases, severities when supplied, and fixed ranges. Release requires dependency remediation and a new compatibility check. The fixed reference is retained for this implementation task. A successful offline build does not prove those advisories harmless.
