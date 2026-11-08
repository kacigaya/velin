# M2 backend handoff

M2 sign-in is implemented and tested offline. The roadmap's live sign-in exit check remains open. No live Tuta request was sent.

## Commits

- `a9a8f12 feat(sdk): wrap persistent session keys`
- `b230313 feat(data): implement persistent sign-in`
- The documentation commit adds this report and the Maven advisory result.

No push or merge was performed.

## Integration

1. Add `implementation(project(":core:data"))` to the app module when wiring the UI.
2. Create one `app.velin.core.data.VelinBackend(applicationContext)` per process.
3. Use `backend.auth: app.velin.core.domain.AuthRepository`.
4. Collect `auth.state` from a lifecycle-aware coroutine.
5. Call the existing suspend methods from a ViewModel coroutine. The repository takes ownership of the password `CharArray`. Do not reuse that array.
6. Call suspend `backend.close()` only when discarding the backend. It cancels polling, closes the local SDK session, and attempts to cancel a pending challenge. It keeps saved credentials. Do not reuse a closed backend.

All disk access, KDF work, HTTP work, and SDK login run on `Dispatchers.IO`. Construction does not read credentials or load native code. Initial state is `Credentials(emptyList(), busy = true)`. Initialization then loads the saved account list. A mutex protects transitions. Generation checks reject stale responses. The backend owns a `SupervisorJob` for initialization and challenge polling. Caller cancellation clears the submitted password. After `signIn` returns a challenge, polling continues in the backend scope. Use `cancelSignIn` to stop it.

`signIn` returns after activation or challenge creation. TOTP POST success does not activate the account. GET polling must confirm approval first. Every challenge allows other-session approval, as in upstream. `securityKey` reports the offered challenge type. Answering with `SecurityKey` returns `SecurityKeyUnsupported`; no WebAuthn request is attempted. Unknown challenge types can use other-session approval only.

`resume` calls the SDK's `login(Credentials)` through `SdkEngine.resume`. Each loaded account has its own SDK instance. The SDK must unlock the user group key before the repository emits `Active`. An invalid resume token emits `Expired` and keeps the saved account. Other errors use the existing `AuthError` values.

`signOut` revokes the remote session before deleting local credentials. A network failure keeps the account and session so the caller can retry. `removeSavedAccount` forgets local credentials and closes that account's local session. It does not revoke the remote session. Use `signOut` for remote revocation.

## Files and storage

- `core/data`: authentication service serialization, repository, encrypted credential store, factory, and offline tests.
- `core/sdk`: persistent-session key wrapping and upstream compatibility vectors.
- `settings.gradle.kts`: registers `:core:data`.
- No app, feature, design-system, or HTML code changed.

Room and SQLCipher wait for M4. M2 stores only a small account list. One atomic encrypted file is sufficient. The file contains login, user ID, access token, and the upstream encrypted passphrase key. The complete list, including addresses, uses AES-256-GCM with a fresh 96-bit IV, a 128-bit tag, and a versioned authenticated context. The key is non-exportable and backed by AndroidKeyStore. It requires no user authentication. Storage is `applicationContext.noBackupFilesDir/credentials.v1`. A corrupt or unreadable file produces an error. It is never silently replaced with an empty account list during a write.

The SDK content store is private memory per SDK instance. It validates names and copies buffers. M2 does not need a persistent SDK cache. Mail caching belongs to M4.

## Verified

1. `./gradlew testDebugUnitTest :core:domain:test assembleDebug` passed. An intermediate repeat hit a Gradle test-output file collision while overlapping checks used the same worktree. The final check ran alone.
2. All 12 repository tests pass. They use only injected transports with reserved example URLs. They cover both KDF selections; credential, challenge, and active transitions; TOTP request fields; approval polling; cancel DELETE; stale creation responses; cancelled TOTP retries; removal during resume; expiry; local removal; logout; password cleanup; unknown KDF rejection; and HTTP 400, 401, 429, 474, and 500.
3. Credential tests use Robolectric's file API and a JVM AES key in place of AndroidKeyStore. They verify storage reopen, encrypted fields, fresh IVs, and tamper rejection.
4. Session key wrapping matches six upstream vectors, covering 128-bit and 256-bit passphrase keys under AES-256 access keys. Existing bcrypt and native Argon2id vectors also pass.
5. Reviewed all added source and tests locally. No delegated reviewer was used because this task is an implementation subtask. `git diff --check` passed.
6. The six resolved public Maven artifacts in the data runtime graph have no OSV findings. See `m2-maven-advisories.json`.

## Source evidence

All upstream paths are relative to `/home/ubuntu/src/tutanota`, commit `adccedea76c7049c78c9404332afda50f84bb05a`. Ports and vectors retain GPLv3 compatibility with Velin.

| Behavior | Upstream source |
|---|---|
| Salt, KDF, verifier, persistent access key, credentials | `src/platform-kit/base/facades/LoginFacade.ts:279-350,479-487,1176-1194` |
| Auth verifier is SHA-256 of the derived key | `src/platform-kit/crypto/encryption/symmetric/SymmetricCipherUtils.ts:35-40` |
| Second-factor POST, DELETE, GET polling and network retries | `src/platform-kit/base/facades/LoginFacade.ts:491-520,929-968` |
| Other-session approval is available on the challenge screen | `src/applications/common/misc/2fa/SecondFactorAuthView.ts:41` |
| TOTP fields and wrong-code handling | `src/applications/common/misc/2fa/SecondFactorAuthDialog.ts:145-169` |
| Wire IDs, model version 158 | `src/entities/sys/TypeModels.js:3573-3648,4300-4373,8037-8306,11703-11742` |
| Numeric attribute keys, string values, association lists | `src/platform-kit/instance-pipeline/TypeMapper.ts:247-275` |
| Service paths and GET `_body` | `src/platform-kit/meta/ServiceDefinitions.ts:20-21`; `src/platform-kit/rest-client/RestClient.ts:70-71` |
| `cv=366.261105.0` | `tuta-sdk/rust/sdk/src/lib.rs:113,160-169` |
| SDK resume and session ID parsing | `tuta-sdk/rust/sdk/src/login/login_facade.rs:92-155,190-205` |
| Session entity URL uses the model name `Session` | `tuta-sdk/rust/sdk/src/entity_client.rs:47-65`; `src/entities/sys/TypeModels.js:7827-7834` |
| Key wrapping: SHA-512 subkeys, AES-CBC without padding, HMAC-SHA256, marker byte | `tuta-sdk/rust/crypto-primitives/src/key.rs:62-73`; `aes.rs:108-122,365-375,410-459,482-538` |
| Wrapping compatibility vectors | `app-android/tutashared/src/androidTest/assets/CompatibilityTestData.json:50-83` |
| Native credential encryption boundary reused as an idea | `app-android/tutashared/src/main/java/de/tutao/tutashared/AndroidKeyStoreFacade.kt:108-145,199-225` |

## Dependencies and limits

No new production dependency is requested or added. The new module declares the existing coroutines version `1.11.0`. It uses Android JSON, AndroidKeyStore, JCA, and AtomicFile. Tests reuse the existing JUnit and Robolectric versions. There is no dependency on `:core:html` or jsoup.

The pinned Rust dependency graph is unchanged. Its previous scan reports 13 affected packages. See `dependency-advisories.json` for advisory IDs, aliases, severities, affected ranges, fixed events, and links. Those findings remain release blockers. The Rust graph was not rescanned in M2. The Maven scan does not establish that the native SDK is free of known vulnerabilities.

## UNVERIFIED

- Live password sign-in, bcrypt account acceptance, TOTP, other-session approval, session expiry, logout, and server acceptance of the pinned client version. No test account exists.
- AndroidKeyStore generation, key invalidation, hardware backing, Android native library loading, and process restart on a device. JVM AES tests do not prove these device behaviors.
- Real server wire acceptance and SDK user/group unlock for newly created sessions. Offline tests prove request construction and repository behavior against source-derived fixtures.
- Security-key sign-in. It is explicitly unsupported.
- Upstream login maintenance beyond M2 authentication: bcrypt-to-Argon2id account migration, rollout/key rotation initialization, event bus startup, and application cache initialization are not implemented. These need separate audited workflows before full production parity. M2 does not mutate account KDF settings.
- Durable offline mail and multi-account concurrent active sessions. One account is active at a time. Saved credentials can contain multiple accounts.
- Polling retries network failures ten times after the initial failure, with a one-second interval between attempts. After retries stop, retry a TOTP answer or restart sign-in. Cancellation prevents local activation even if remote cleanup fails. A lost server response can leave an untracked remote session, as with other interrupted session-creation clients.
- JVM strings, JSON buffers, provider internals, and FFI copies cannot all be erased. Submitted password arrays, derived key buffers, access-key buffers, wrapping subkeys, and explicit credential plaintext buffers are cleared after use. The plaintext password is never persisted or logged.

The roadmap must retain the live M2 exit check until an owner supplies a test account and authorizes device checks.
