# Production protocol pin

`tools/backend/upstream.json` is the source of truth. Use release tag
`tutanota-release-366.261105.0`, commit
`332e57461cdc4fe4a4ad9bdc8555f56df02931c0`. Never pin master.

The release has sys model 157 and tutanota model 117
(`src/entities/sys/ModelInfo.ts:2`, `src/entities/tutanota/ModelInfo.ts:2`).
`Cargo.toml:2` supplies client version `366.261105.0`.
`TutaRelease.kt` contains checked copies. Authentication uses these constants.
`check-pin.py` checks source hashes, both model versions, client version,
and every numeric auth field against release `src/entities/sys/TypeModels.js`.
Gradle runs this check before native artifact verification. `TutaReleaseTest`
also compares both Kotlin versions with the exported ModelInfo files.

HTTP 474 means invalid software version
(`tuta-sdk/rust/sdk/src/rest_error.rs:373`). It propagates as
`AuthError.InvalidSoftwareVersion` or `Failure.InvalidSoftwareVersion`.
These existing domain states let the UI show a client update error.
HTTP 400 means bad request (`rest_error.rs:351`). It can also occur with a
rejected model version. The supplied live check establishes this symptom,
but supplies no unique response discriminator. Velin does not classify every
400 as outdated. Automatic classification of that case remains UNVERIFIED.
In particular, a bad second-factor code must retain its own error state.

## Update the pin

1. Select the production release tag. Confirm its commit against upstream.
2. Export that commit without changing the reference checkout HEAD.
3. Update `upstream.json` with the tag, full commit, and SHA-256 input hashes.
4. Compare each auth field ID and name with the release TypeModels files.
5. Update `TutaRelease.kt` from ModelInfo and Cargo.toml. Check overlay APIs
   against the release. Keep crypto checks fail closed.
6. Run `tools/backend/build-sdk.sh`. It builds both Android ABIs and host code.
7. Run the offline tests and Rust tests. Record artifact checksums.
8. Ask the authorized UI engineer to check sign-in and reads on a device.

Earlier backend handoffs cite the old master pin as historical evidence.
They do not describe the current production pin. This document supersedes
those version and build instructions. jsoup is owner-approved.

Native artifacts remain ignored build output. `SHA256SUMS` records output
hashes; `INPUTS-SHA256` records build input hashes. The tracked pin records
source input hashes. No new production dependency is added by this change.

The build checks that the release tag resolves to the exact pinned commit.
The build guard also needs Python 3 and Node.js. Node reads the hash-checked
JavaScript TypeModels without installing a package.

## Dependency scan

`release-native-advisories.json` records the OSV scan of all 327 registry
packages in this release lockfile. It reports 17 affected packages and 35
advisory records. Each record includes aliases, supplied severity, ranges,
fixed events, and links. This includes build and test packages; it does not
prove reachability in the Android library. The lockfile is unchanged.
The findings remain release blockers. No dependency was upgraded here.

## Auth wire evidence

All paths below refer to the pinned release commit. IDs occur in
`AuthService.kt`; the build guard checks this full set.

| ID | Field | Release source |
| --- | --- | --- |
| 418 | `_format` | `src/entities/sys/TypeModels.js:3576` |
| 419 | `mailAddress` | `src/entities/sys/TypeModels.js:3585` |
| 422 | `salt` | `src/entities/sys/TypeModels.js:3619` |
| 542 | `_format` | `src/entities/sys/TypeModels.js:4303` |
| 1189 | `type` | `src/entities/sys/TypeModels.js:7784` |
| 1212 | `_format` | `src/entities/sys/TypeModels.js:8040` |
| 1213 | `mailAddress` | `src/entities/sys/TypeModels.js:8049` |
| 1214 | `authVerifier` | `src/entities/sys/TypeModels.js:8058` |
| 1215 | `clientIdentifier` | `src/entities/sys/TypeModels.js:8067` |
| 1216 | `accessKey` | `src/entities/sys/TypeModels.js:8076` |
| 1217 | `authToken` | `src/entities/sys/TypeModels.js:8085` |
| 1218 | `user` | `src/entities/sys/TypeModels.js:8105` |
| 1221 | `accessToken` | `src/entities/sys/TypeModels.js:8139` |
| 1222 | `challenges` | `src/entities/sys/TypeModels.js:8150` |
| 1223 | `user` | `src/entities/sys/TypeModels.js:8160` |
| 1230 | `type` | `src/entities/sys/TypeModels.js:4312` |
| 1231 | `u2f` | `src/entities/sys/TypeModels.js:4332` |
| 1232 | `session` | `src/entities/sys/TypeModels.js:4342` |
| 1234 | `_format` | `src/entities/sys/TypeModels.js:8237` |
| 1235 | `accessToken` | `src/entities/sys/TypeModels.js:8246` |
| 1238 | `secondFactorPending` | `src/entities/sys/TypeModels.js:8280` |
| 1243 | `otpCode` | `src/entities/sys/TypeModels.js:4321` |
| 1417 | `recoverCodeVerifier` | `src/entities/sys/TypeModels.js:8094` |
| 1756 | `_format` | `src/entities/sys/TypeModels.js:11706` |
| 1757 | `session` | `src/entities/sys/TypeModels.js:11717` |
| 1905 | `webauthn` | `src/entities/sys/TypeModels.js:4352` |
| 2133 | `kdfVersion` | `src/entities/sys/TypeModels.js:3628` |

## Verification

The release-only build passed for arm64-v8a, x86_64, and the host library.
`repin-artifacts.sha256` records that build before the M3b overlay.
The negative model-drift check failed as expected.

`ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew testDebugUnitTest :core:domain:test :core:html:test assembleDebug --console=plain`
passed (293 tasks). The first attempt had no SDK path; the command above
fixed that environment error. Native tests ran through the host library.
UniFFI could not find optional ktlint; binding generation completed.

Review ran locally because this is an implementation subtask with no child
agents. `git diff --check` and Bash syntax checks passed. Device and live
protocol acceptance remain UNVERIFIED. No live Tuta API request was sent.
