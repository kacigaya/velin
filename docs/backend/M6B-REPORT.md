# M6b backend implementation

Protocol source: release `tutanota-release-366.261105.0`, commit
`332e57461cdc4fe4a4ad9bdc8555f56df02931c0`. No live Tuta request ran.
No mail was sent. No production dependency was added.

## Implementation commits

1. `d0eab51` `feat(sdk): add verified internal send protocol`
2. `39b5e51` `feat(backend): wire recipient trust and send`

The documentation commit records this report and final artifact hashes.

## Recipient and identity contracts

`MailRepository` adds default methods. Existing implementations remain source
compatible:

```kotlin
suspend fun resolveRecipient(account: AccountId, address: String): Outcome<Recipient>
suspend fun recipientFingerprint(account: AccountId, address: String): Outcome<String>
suspend fun verifyRecipient(account: AccountId, address: String, fingerprint: String): Outcome<Unit>
suspend fun untrustRecipient(account: AccountId, address: String): Outcome<Unit>
```

1. Resolve typed addresses with `resolveRecipient`. Draft reload still returns
   Unknown recipients. Contact suggestions stay empty until contacts are loaded.
2. `Recipient.keyState` distinguishes unresolved, no identity entry, TOFU,
   manual trust, unsupported trust storage, and mismatch. The default is
   Unresolved. Existing constructor arguments keep their meaning.
3. Current encryption keys are always fetched. Current-key results and trust
   decisions are not cached. Historical key lookup is outside this API, so no
   historical encryption-key cache was added. Identity keys persist per account.
   Lookup concurrency is limited to five requests per session.
4. PublicKeyService 404 means no encryption key. An absent address on a Tuta
   domain remains Internal. Other absent addresses are External. Transport,
   rate-limit, malformed-key, and trust-storage errors stay failures.
5. A signature without an identity key, a missing signature for an existing
   identity, or an invalid signature produces Mismatch. It remains an Internal
   recipient. Mismatch blocks send. NoEntry and verified TOFU allow send, as in
   the release. Manual trust is a separate state. These are backend states;
   this change makes no presentation decision.
6. Identity entries use AES-GCM with a non-exportable Android Keystore key.
   The authenticated data binds the file to its account and storage format.
   Files live under `noBackupFilesDir/identity-trust`. An atomic write retains
   the first identity. Lookup never replaces it or downgrades manual trust.
   Corruption fails closed. It never clears the trust file.
7. Fingerprints use SHA-256 over identity version, Ed25519 type, and key bytes.
   Manual verification requires the exact expected fingerprint. Explicit
   `untrustRecipient` removes the entry. The next resolution applies TOFU again.
   These actions need deliberate user interaction in the UI owner’s work.

The local trust store is separate from the official client's trust store.
Manual trust in one client does not imply manual trust in the other.

## Send contract

`sendDraft(account, id)` keeps its signature. It loads the authenticated saved
mail and details. It does not use recipient type flags supplied by the composer.

1. Preflight rejects scheduled mail, non-confidential mail, any attachments,
   reply/forward conversation metadata, calendar methods, and custom reply-to
   headers. It checks the enabled sender and draft owner group.
2. Resolve every To, Cc, and Bcc recipient against fresh encryption keys.
   Missing Tuta recipients and the system address block send. External
   recipients return Unsupported. A mismatch blocks the whole request.
3. Generate one 256-bit bucket key. Wrap the existing draft session key with
   that key. Encrypt the bucket key for each internal recipient with the
   release SDK's RSA or TutaCrypt implementation. Keys stay inside Rust.
4. Use the user group for a personal sender's asymmetric authentication key.
   Use the mail group for a shared sender. Read `sendPlaintextOnly` through
   the release RootInstance lookup and validate returned IDs and errors.
5. Set the encrypted sender authentication status only when every recipient
   uses TutaCrypt. Both legacy and nested SendDraftData fields match the release.
   Aggregate IDs are assigned before serialization. SendDraftService runs once.
6. The release's key wrapper uses CBC+HMAC for 256-bit keys even on an AEAD
   account. SendDraftData has no encrypted model attributes. It must not apply
   the draft attribute-encryption scheme to these wrapped keys.
7. On success, remove the cached draft from pages and open conversations.
   Refresh Sent and Inbox through the existing sync. A failed response may
   follow a successful server send. There is no automatic send retry.

`Failure.SendBlocked` carries a typed reason and recipient positions in the
concatenated To, Cc, Bcc list. It never carries addresses.
`Failure.RecipientResolution` distinguishes invalid input, rate limiting,
invalid key data, and unavailable trust storage.

`Failure.Unsupported` retains its companion value for old default results.
`Failure.Unsupported.Operation` carries a typed reason, including separate
calendar-mail and custom reply-to cases. Consumers must use
`failure is Failure.Unsupported` to include these new results. No UI module
was changed.

External sends, password-protected external sends, attachment upload and send,
reply/forward, calendar mail, custom reply-to headers, scheduling, and undo-send
are unsupported. Language is fixed to `en`; internal sends create no external
notification. No external password or unencrypted fallback exists.

## Release evidence

All paths below refer to the pinned commit. Numeric service fields come from
its generated Rust entities and matching TypeModels, not from master.

| Behavior | Upstream source |
| --- | --- |
| Address cleanup | `src/platform-kit/utils/FormatUtils.ts:133` |
| Bounded resolution and recipient states | `src/applications/common/api/main/RecipientsModel.ts:46`, `:63`, `:161` |
| Tuta domain fallback | `src/applications/common/mailFunctionality/SharedMailUtils.ts:221`; `src/platform-kit/app-env/TutanotaConstants.ts:51` |
| Current-key fetch and historical-only cache | `src/platform-kit/base/base-crypto/PublicEncryptionKeyProvider.ts:52` |
| RSA version constraint and key variants | `src/platform-kit/base/base-crypto/PublicEncryptionKeyProvider.ts:89`, `:158` |
| Signature canonicalization | `src/platform-kit/base/base-crypto/PublicKeySignatureFacade.ts:52`, `:157`; `src/platform-kit/utils/Encoding.ts:413` |
| Missing identity/signature and trust states | `src/platform-kit/base/facades/lazy/KeyVerificationFacade.ts:63` |
| Identity lookup, system exception, TOFU | `src/platform-kit/base/base-crypto/PublicIdentityKeyProvider.ts:89` |
| Persistent trust and manual trust | `src/app-kit/local-store/LocalIdentityKeyTrustDatabase.ts:39`, `:69`, `:126` |
| Fingerprints and explicit trust removal | `src/platform-kit/base/facades/lazy/KeyVerificationFacade.ts:37`, `:137` |
| Send construction and nested fields | `src/applications/common/api/worker/facades/lazy/MailFacade.ts:747` |
| Recipient key data, missing recipients, mismatch | `src/applications/common/api/worker/facades/lazy/MailFacade.ts:951`; `src/platform-kit/base/base-crypto/CryptoFacade.ts:658` |
| Sender group and TutaCrypt sender status | `src/applications/common/api/worker/facades/lazy/MailFacade.ts:966`, `:1030`; `src/platform-kit/app-env/TutanotaConstants.ts:408`, `:464` |
| RSA/TutaCrypt dispatch | `src/platform-kit/base/base-crypto/AsymmetricCryptoFacade.ts:262`; `tuta-sdk/rust/sdk/src/crypto/asymmetric_crypto_facade.rs:238` |
| Key wrapping scheme | `src/platform-kit/crypto/instance-pipeline-crypto/SymmetricCipherFacade.ts:220` |
| Property root lookup | `src/platform-kit/network/EntityClient.ts:163` |
| Send fields | `tuta-sdk/rust/sdk/src/entities/generated/tutanota.rs:1234`, `:1324`, `:4430` |
| Identity service fields | `tuta-sdk/rust/sdk/src/entities/generated/sys.rs:6131`, `:6156`, `:6178` |
| Send mismatch recovery | `src/applications/common/mailFunctionality/SendMailModel.ts:1144` |

## Offline verification

Final checks passed on 2026-11-09. No live Tuta check ran.

1. Native release builds and binding generation passed for arm64-v8a,
   x86_64, and the host. Final library hashes passed `sha256sum -c`.
2. All 43 selected Rust tests passed. The selector excluded 304 other unit
   tests and all integration tests. The first test compile found three
   invalid fixture constructors in the saved patch. They were corrected
   before this passing run.
3. The full Gradle command passed: 229 tasks, including debug unit tests and
   `:app:assembleDebug`. XML reports contain 143 JVM tests, with zero failures,
   errors, or skipped tests. SDK tests used the final host library.
4. Release vector regeneration passed after `npm ci --ignore-scripts`.
   All four send fixtures matched the saved JSON byte for byte. Draft JSON
   and HTML fixtures remained unchanged. Rust compared every send wire byte.
5. Pin verification, overlay input hashes, Bash/Node/Python syntax checks,
   artifact checksums, and `git diff --check` passed.
6. A fresh OSV scan covered all 327 registry packages in the release lockfile.
   It found the same 17 affected packages and 35 advisory records. Advisory
   fields are recorded in `release-native-advisories.json`; only four rustls
   advisory modification timestamps differ from the fresh response. A fresh
   scan of 15 resolved public Maven packages found no advisory.
7. Optional ktlint was absent. Rust reported existing lifetime and dead-code
   warnings and a num-bigint-dig future-compatibility warning. APK packaging
   retained three native libraries without stripping. These warnings did
   not fail validation.

Commands and vector coverage:

1. Native build: `TUTA_REFERENCE=/home/ubuntu/src/tutanota tools/backend/build-sdk.sh`.
   Artifact hashes are in `m6b-artifacts.sha256`. Optional ktlint is unavailable.
2. Native overlay tests:
   `CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0 tools/backend/test-read-overlay.sh`.
3. Android and JVM checks:
   `./gradlew testDebugUnitTest :core:domain:test :core:html:test :app:assembleDebug --console=plain --max-workers=2`.
4. Vectors: release `npm ci --ignore-scripts`, then
   `TUTA_RELEASE=/home/ubuntu/src/tutanota-release node tools/backend/vectors/run.mjs`.
   Four signed RSA send fixtures cover 128/256-bit draft session keys and
   CBC/AEAD account settings. Rust checks signature input, Ed25519 verification,
   RSA ciphertext, wrapped session keys, and every SendDraftData wire byte.
   Node supplies the real Ed25519 primitive; the release supplies signature
   canonicalization. These fixtures do not claim to run the release Ed25519 WASM.
5. The send fixtures do not cover deterministic TutaCrypt encapsulation. Its
   pinned native implementation uses the C library's random source for ML-KEM.
   It cannot use the injected Rust randomizer for byte-for-byte regeneration.
   This change reuses that implementation. It adds no alternate PQ primitive.
6. OSV scanned 327 exact public Rust packages. The same 17 packages and 35
   advisory records remain. See `release-native-advisories.json` for package
   versions, IDs, ranges, fixed events, and links. These remain release blockers.
   The upstream development install also reports 31 npm advisories. No package
   or lockfile was changed. Server acceptance and device behavior are unverified.

## Owner live check

Run only after the owner chooses to test this build. Use only the account's
own enabled address. Do not use another account, a contact, or an external address.

1. Sign in. Set To to the account's own enabled address. Leave Cc and Bcc empty.
   Use a new confidential draft. Add no attachment, reply-to header, or schedule.
2. Through `backend.mail.resolveRecipient(account, ownAddress)`, check Internal.
   Stop on Mismatch or any failure. If an identity exists, compare
   `recipientFingerprint` with the official client's fingerprint before calling
   `verifyRecipient`. Never remove trust just to bypass a mismatch.
3. Save a subject such as `Velin M6b self-check` and a short Unicode body.
   Keep the returned DraftId. Reload that exact draft and compare its content
   in the official client before sending.
4. Call `backend.mail.sendDraft(account, draftId)` once. Stop on failure.
   If the response is lost or fails, inspect the official client's Drafts,
   Sent, and Inbox before any retry. Do not retry automatically.
5. Confirm one sent mail and one received self-mail. Compare subject, body,
   confidentiality, sender, and recipient. Confirm the draft left Drafts.
6. Restart the app. Resolve the same address. Confirm the trust state survives.
   Switch accounts and confirm the other account has separate trust entries.
7. If both CBC and AEAD accounts are available, repeat only with each account's
   own address. Do not force the rollout or downgrade encryption for a test.

## Files and integration

1. `core/domain/.../Models.kt` and `Repositories.kt`: recipient trust states,
   typed failures, and source-compatible default repository calls.
2. `core/sdk/.../SdkEngine.kt` and `SdkMailReader.kt`: native calls, account
   trust callback, fingerprint calculation, and typed error mapping.
3. `core/data/.../IdentityTrustStore.kt`, `VelinBackend.kt`, `MailReadSource.kt`,
   `SdkMailReadSource.kt`, and `BackendReadRepository.kt`: protected trust
   persistence, session wiring, recipient resolution, send, and cache removal.
4. Data tests cover persistence, corruption, account isolation, manual trust,
   typed errors, and cache invalidation only after successful send.
5. `tools/backend/overlay/recipient_source.py`, `recipients.rs`, `send.rs`,
   `send_client.rs`, and `send_tests.rs`: native protocol port and offline tests.
6. `tools/backend/build-sdk.sh`: apply the new overlay before native builds.
7. `tools/backend/vectors/{drafts.ts,sends.ts,sends.json,run.mjs,README.md}`:
   shared seeded randomizer, release-generated fixtures, and regeneration steps.
8. `docs/ROADMAP.md`, `M6-DETAILS.md`, `DRAFT-ENCRYPTION.md`, this report, and
   `m6b-artifacts.sha256`: scope, evidence, verification, and final native hashes.

No app, feature, or design-system file changed. The UI owner must call
`resolveRecipient` for typed addresses, handle `Recipient.keyState` and typed
failures, and wire deliberate fingerprint verification and trust removal.
Use `is Failure.Unsupported` when matching all unsupported operations.
`sendDraft` independently resolves saved recipients again, so stale composer
state cannot bypass verification. Contact suggestions remain empty.

## Deliberate limits

Recipient addresses are limited to 254 UTF-8 bytes at the native boundary.
The send preflight accepts at most 1,000 recipients. The local trust file
accepts at most 10,000 entries and 4 MiB of ciphertext. Exceeding a limit
blocks the operation. Larger trust stores need indexed persistence in M4/M8.
This store assumes one active backend instance per app process. The existing
session mutex serializes repository operations and account changes.
Trust storage uses the existing Android Keystore credential key. Removing
app data or that key removes access to local trust. There is no trust sync.

Review ran locally because this implementation task excludes further
delegation. No additional reviewer ran. No live Tuta API call ran.
