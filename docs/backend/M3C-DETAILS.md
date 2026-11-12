# M3c mail reads

This is a partial implementation. M3 remains incomplete. No live Tuta request was sent. No app, feature, or design-system source changed. OkHttp is confined to the counter connection in `:core:network`.

## Implemented paths

Received details use an archive read token and a blob-server request. The crypto client supplies the decrypted parent mail's owner-encrypted session key and version to MailDetailsBlob. Draft details use MailDetailsDraft with the same provider. The SDK checks entity and nested address/body errors before exporting text. The data repository sends this text through the existing core HTML sanitizer. External image consent and blocking use the existing repository code.

Conversation loading reads all ConversationEntry records from the selected entry's list. It excludes entries without a mail, removes duplicate mail IDs, and sorts summaries by received time. The existing repository loads each message body and emits messages by sent time, oldest first. A missing selected entry fails instead of returning another conversation.

Stored phishing status 1 produces the phishing finding. Wire mail authentication failures 1, 3, and 4 produce an authentication failure finding. Encryption status 5 produces the deprecated-key finding. Sender authentication always remains Unknown. Other messages carry an uncertain finding. Local phishing marker matching is not ported.

Ordinary HTTP requests retain the original service-origin restriction. Only the new SDK blob callback can use the server URLs from the authenticated blob-token response. Those requests require HTTPS. Redirects remain disabled. The existing 32 MiB HTTP response bound applies. Blob read tokens are not cached. The SDK tries returned blob servers after HTTP errors. Token refresh/retry is not ported.

Debug SDK exceptions retain the underlying HTTP or REST-client variant name. Internal errors retain only reviewed static messages. All other internal text becomes `redacted`. Release SDK exceptions omit diagnostics. Protocol failures carry the safe diagnostic to the domain. Offline, unauthorized, decryption, and unsupported domain failures retain their existing singleton contracts, so those domain values have no per-error diagnostic. No raw exception cause is retained.

## Attachment and counter limits

1. Attachment metadata uses each file's owner-encrypted key. A missing key uses the matching instance key from the parent mail's bucket. A download names its parent mail. The SDK checks that the mail lists the file. The data layer finds the parent in its cached mail, so mail restored from the offline store downloads too. The session map of file-to-mail IDs stays as a fallback.
2. Each `BlobId` in the blob GET request gets a random aggregate ID. Without it, serialization fails before the request.
3. A download goes into a new random folder under `cacheDir/mail-attachments`. The sender's file name is cleaned to letters, digits, `.`, `_`, `-` and spaces, without leading dots, and cut at 200 UTF-8 bytes with its extension kept. Downloads older than one hour are deleted on the next download and when an account opens, which includes each app start. Sign-out deletes all of them. Limit: a file opened in a long session stays until one of these events.
4. The app opens a download read-only through a FileProvider limited to that folder. An invalid or APK MIME type opens as `application/octet-stream`.
5. The reader hides only images that the body shows. Some senders give every file a Content-ID, so a Content-ID alone does not hide a file.
6. Downloads accept at most 20 MiB of plaintext and 100 blobs. Size checks precede the blob request. The encrypted response has a 21 MiB parser limit and the transport has a 32 MiB limit. Blob IDs use the release base64ext encoder. Lengths, count, duplicate IDs and trailing bytes are checked. The plaintext must not be larger than `File.size`. Files of external mail carry their encrypted size there, so it is only an upper bound. Upstream does not check it. This was found live on 2026-11-12 with a 259,508-byte PDF whose `File.size` was its 259,569-byte blob. The release AES implementation decrypts each chunk. Mixed archives in a single file fail explicitly. Token refresh/retry is not ported. Existing inline-image MIME and signature checks remain in force.
7. Conversation loading has no upstream fallback to the selected mail after authorization failure. The repository contract supplies a conversation entry ID, not the selected mail ID. It fails explicitly.
8. CounterService GET is unused. A session owns the `/event` WebSocket. It receives `unreadCounterUpdate` with numeric WebsocketCounterData fields. Counts are grouped by mail group and folder ID. Invalid data ends the connection. Disconnect clears counts. Reconnect uses delays from 1 to 60 seconds. Account replacement and sign-out cancel the socket and clear state. Counter notifications refresh the mailbox snapshot through the active session.
9. Counter parsing accepts at most 256 KiB, nesting depth 8, 2048 rows per update, and 4096 stored counters. OkHttp assembles a message before the callback applies this limit. This does not bound OkHttp's receive allocation. Full entity events, durable cursors, missed-event replay, and phishing-marker events remain M4.
10. Sender cryptographic authentication and local phishing detection are NOT IMPLEMENTED. Stored status alone never produces Authenticated.
11. Decrypted compressed strings have a 32 MiB output limit. Larger content fails decryption. This bounds each decoded field, not the total memory used by a message.

## Pinned source evidence

Every path below refers to release commit `332e57461cdc4fe4a4ad9bdc8555f56df02931c0`.

| Behavior | Release file and line |
| --- | --- |
| Received details and provider | [src/applications/common/api/worker/facades/lazy/MailFacade.ts:1293](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L1293) |
| Draft details | [src/applications/common/api/worker/facades/lazy/MailFacade.ts:1335](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L1335) |
| Blob entity GET | [src/platform-kit/network/EntityRestClient.ts:326](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/network/EntityRestClient.ts#L326) |
| Archive read token | [src/platform-kit/network/BlobAccessTokenFacade.ts:200](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/network/BlobAccessTokenFacade.ts#L200) |
| Blob query authentication and version | [src/platform-kit/network/BlobAccessTokenFacade.ts:255](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/network/BlobAccessTokenFacade.ts#L255) |
| Body field preference | [src/applications/common/api/common/CommonMailUtils.ts:13](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/common/CommonMailUtils.ts#L13) |
| Conversation entry list and sort | [src/applications/mail-app/mail/view/ConversationViewModel.ts:232](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/mail-app/mail/view/ConversationViewModel.ts#L232) |
| Phishing status values | [src/entities/tutanota/Utils.ts:209](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/entities/tutanota/Utils.ts#L209) |
| Finding precedence | [src/applications/mail-app/mail/view/MailViewerViewModel.ts:416](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/mail-app/mail/view/MailViewerViewModel.ts#L416) |
| WebSocket login parameters and endpoint | [src/app-kit/local-store/event/EventBusClient.ts:209](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/app-kit/local-store/event/EventBusClient.ts#L209) |
| Counter message decode | [src/app-kit/local-store/event/EventBusClient.ts:374](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/app-kit/local-store/event/EventBusClient.ts#L374) |
| Counter state update | [src/applications/mail-app/mail/model/MailModel.ts:455](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/mail-app/mail/model/MailModel.ts#L455) |
| Attachment key provider | [src/applications/common/api/worker/facades/lazy/MailFacade.ts:1587](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L1587) |

## Additional release evidence

1. [Attachment archive type](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/entities/sys/Utils.ts#L21).
2. [Blob requests and chunk decryption](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/BlobFacade.ts#L973).
3. [Blob response framing](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/BlobFacade.ts#L1195).
4. [Numeric counter data](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/tuta-sdk/rust/sdk/src/entities/generated/sys.rs#L3519).
5. [Annotated numeric JSON keys](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/instance-pipeline/TypeMapper.ts#L97).

## Live checks for the owner

These checks remain UNVERIFIED. Use the pinned release and a test account.

1. Open received mail without attachments. Check blob-token POST, HTTPS blob GET, subject, recipients, date, and decrypted body.
2. Open a draft without attachments. Check MailDetailsDraft loading and body decryption.
3. Open a conversation with received mail, sent mail, and a draft. Check order and each body.
4. Open mail with external images. Check blocked loads before consent and permitted image loads after consent.
5. Check stored phishing and deprecated-key examples. Check that no message claims Authenticated.
6. Open received and draft mail with attachments. Check names, sizes, MIME types, downloads, and cid images. Compare downloaded bytes with the official client. Check rejection above 20 MiB.
7. Sync folders. Check initial unread counts and updates after a second client changes read state. Disable and restore the network. Check reconnect. Switch accounts and sign out. Check socket cleanup and no count leakage.
8. Cause an HTTP read failure in a debug build. Check that the failure reports its variant and contains no credentials or mail text.

## Encoding evidence

Blob framing encodes each nine-byte ID with [the release base64ext encoder](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/tuta-sdk/rust/sdk/src/util.rs#L37). It uses the sortable alphabet from [Encoding.ts](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/utils/Encoding.ts#L59).

## Dependency checks

OkHttp `5.5.1` is the stable release dated 2026-10-22 in the [official changelog](https://github.com/square/okhttp/blob/parent-5.5.1/CHANGELOG.md). The owner approved this production dependency. It is declared in the version catalog. No OkHttp type crosses `:core:network`. HTTP requests still use HttpURLConnection. TLS verification uses platform defaults. Redirects are disabled. No socket URLs or failure text are logged.

`m3c-maven-packages.json` records exact public packages resolved for the network runtime. `m3c-maven-advisories.json` records the OSV query on 2026-11-08. No advisory was returned for those 14 packages, including OkHttp `5.5.1` and Okio `3.18.1`. This is a point-in-time result. The unchanged Rust lockfile was also rescanned: 327 packages, 17 affected packages, 35 records. Existing release blockers in `release-native-advisories.json` remain.

## Offline verification

The final native build passed for arm64-v8a, x86_64, and host. `m3c-artifacts.sha256` records the three libraries. The release pin check passed. The reference checkout HEAD did not change.

`./gradlew testDebugUnitTest :core:domain:test :core:html:test assembleDebug --console=plain --max-workers=2` passed: 295 tasks and 106 recorded JVM tests, with no test failure. This includes the existing inline-image MIME, signature, size, and private-file checks. No live request or device test ran.

`tools/backend/test-read-overlay.sh` passed all 13 matching Rust tests. They cover parent keys for received and draft details, token request fields, blob framing and truncation, AES tamper rejection, final file size, allocation limits, secure-external rejection, cursors, and panic containment. The other 304 upstream unit tests and all upstream integration tests were filtered out.

Review ran locally across the changed source, tests, and contracts. Bash syntax checks passed. The whitespace check reports one trailing blank line in `crypto_read.rs`; it has no runtime effect. Generated bindings could not use optional ktlint. Binding generation still passed.

Initial failures were corrected before the final checks. Attachment key resolution needed an explicit SDK error conversion. Two Rust mocks needed `Arc<TypeModel>`. An early Gradle compile ran before native artifact generation completed. None of these failures remains in the final verification run.
