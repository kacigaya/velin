# M3b backend handoff

M3b is incomplete. The completed slice adds SDK-backed folders and mail-list paging. Received bodies remain the next priority. Do not mark M3 complete.

No live Tuta request was sent. No UI, app, feature, design-system, or HTML source changed. The reference checkout remains read-only.

## Implementation

`VelinBackend` now selects `SdkMailReadSource` by default. Callers can still inject `MailReadSource`. The auth repository admits reads only for its active account. It holds its session mutex until the read completes. Sign-out, account replacement, and close cannot dispose that session during a read. A missing session returns `Failure.Unauthorized`.

`SdkMailReader` is the Kotlin SDK boundary. Generated SDK entities and keys stay inside `:core:sdk`. Folder and sender field errors fail closed. The adapter exports no sender-authentication claim.

The native build exports the pinned source, appends `tools/backend/overlay/mail_read.rs`, and runs `guard-crypto.py`. It never edits the reference. The overlay adds typed folder loading, read-only unread counters, descending MailSetEntry ranges, and single-mail loading. Folder loading uses all ranges instead of the upstream first 100 entries. Every new read export catches unwinding Rust panics and returns an SDK error. This does not guard process aborts or out-of-memory termination.

The secure-external `todo!` becomes a session-key error that the overlay
propagates as `ApiCallError::VelinSecureExternalUnsupported`,
`SdkFailure.SecureExternalUnsupported`, and `Failure.Unsupported`.
The marker is local to this guard; no remote error text controls that mapping. Secure-external mail cannot produce a successful summary through this branch. Sender authentication is not ported. The source does not return `MailMessage` or authenticated findings.

Folder kinds follow Rust values 0 through 7. TypeScript adds labels (8), imported folders (9), and scheduled folders (10). Labels are separate from folders. The aggregate All set is omitted because the domain has no All kind. Unknown kinds fail closed. Custom parent links pass to the existing tree validator. Counters use `monitor/counterservice` GET with counter type 2, mail-group row, and folder-element column. Invalid or missing counter results fail the mailbox read.

Pages contain at most 50 source entries. The source cursor is the last MailSetEntry custom ID. A full page permits another request; an empty final request ends an exact multiple of 50. The initial custom upper bound is 340 underscores. Existing repository code orders summaries by received time. Labels come from each mail's sets. Each summary represents one mail. Its conversation identifier is its own ConversationEntry tuple and its size is 1. Cross-mail conversation grouping is not implemented. Sender aliases are empty.

## Source evidence

All upstream paths below refer to `332e57461cdc4fe4a4ad9bdc8555f56df02931c0` under `/home/ubuntu/src/tutanota`.

| Behavior | Source |
| --- | --- |
| User mailbox membership and folders | `tuta-sdk/rust/sdk/src/mail_facade.rs:56-89` |
| Typed decrypted load and ranges | `tuta-sdk/rust/sdk/src/crypto_entity_client.rs:72-132` |
| Rust system kinds | `tuta-sdk/rust/sdk/src/folder_system.rs:14-23` |
| Additional kinds and reply values | `src/entities/tutanota/Utils.ts:16-47` |
| MailSet fields | `tuta-sdk/rust/sdk/src/entities/generated/tutanota.rs:837-865` |
| Entry custom ID and mail pointer | `tuta-sdk/rust/sdk/src/entities/generated/tutanota.rs:3384-3396` |
| Custom upper bound | `src/platform-kit/meta/EntityUtils.ts:56` |
| Counter GET request and result | `src/platform-kit/network/CounterFacade.ts:11-19` |
| Unread counter type | `src/entities/monitor/Utils.ts:4` |
| Counter row and column | `src/applications/common/api/worker/facades/lazy/MailFacade.ts:935-943` |
| Incomplete bucket authentication and secure external panic | `tuta-sdk/rust/sdk/src/crypto/crypto_facade.rs:288-345` |
| Received and draft key providers | `src/applications/common/api/worker/facades/lazy/MailFacade.ts:1293-1344` |
| Conversation entry traversal | `src/applications/mail-app/mail/view/ConversationViewModel.ts:219-249` |

## NOT IMPLEMENTED

1. Received MailDetailsBlob loading and parent-mail key providers.
2. ConversationEntry traversal and grouping.
3. Attachment metadata, bounded blob download, and file decryption.
4. Draft MailDetailsDraft loading.
5. Phishing, mail authentication, deprecated-key, and external-content wire findings.
6. Sender cryptographic authentication. No mail is reported as authenticated.
7. Sender aliases, shared-mailbox enumeration, and aggregate All folders.

Bodies return `Failure.Decryption`. Conversation, message metadata, and attachments return `Failure.Unsupported`. These failures are intentional. There is no empty successful body or file.

## Dependencies

No production dependency was added. jsoup remains confined to `:core:html`; owner approval is confirmed. The pinned Cargo.lock is unchanged. `release-native-advisories.json` scans all 327 registry packages in that lock, including workspace and test packages. It reports 17 affected packages and 35 advisory records. This is not proof that every advisory affects the shipped library. It is not a Maven scan.

## UNVERIFIED

Live authentication, mailbox access, counter acceptance, cursor acceptance, and real-mail decryption remain unverified. This implementation run used no account. The UI engineer owns live checks. Android native loading, Keystore behavior, and device execution remain unverified. Offline tests do not establish live interoperability.

## Verification

The release build passed for arm64-v8a, x86_64, and host. The final artifact
hashes are in `m3b-artifacts.sha256`. The release-only hashes remain in
`repin-artifacts.sha256`.

`ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew testDebugUnitTest :core:domain:test :core:html:test assembleDebug --console=plain`
passed after the overlay (293 tasks). The source tests cover folders,
parents, labels, counters, custom entry cursors, page endings, missing
sessions, unknown kinds, and secure-external failure mapping. SDK tests check
the new FFI error mapping. Existing auth and HTML tests pass.

Local review covered all changed and new files. `git diff --check`, pin
verification, and Bash syntax checks passed. No child reviewer was used.
The reference checkout HEAD remains `adccedea76c7049c78c9404332afda50f84bb05a`.
Only exported release source was changed.

`tools/backend/test-read-overlay.sh` passed all five Velin Rust tests.
They verify MailSet numeric parsing, unread request fields, descending custom
cursors, panic containment, and secure-external rejection. Other upstream
Rust tests were filtered out; no live integration test ran.

The unused `UnavailableMailReadSource` was removed. The final Gradle command
above passed again after that removal (293 tasks, 13 seconds), after Rust
compilation ended.

Intermediate failures were resolved:

1. The recovered Rust fixture omitted nullable serde byte fields 434 and
   1847. The release struct requires them. The fixture now supplies null.
   Production parsing was not changed.
2. An existing conversation-flow test timed out during concurrent Rust
   compilation. The final serial run passed without changing its timeout.
3. The first release build read an edit to the running shell script and
   exited with an unexpected EOF. The complete rerun passed. Bash syntax
   checks also pass.

No production dependency was added. No UI source was changed. No push or
merge was performed. HTTP 400 model-version rejection still lacks a unique
verified discriminator; see `RELEASE-PIN.md`.
