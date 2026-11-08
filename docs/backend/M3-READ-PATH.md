# M3 read-path handoff

M3 is incomplete. This branch supplies an offline read pipeline, domain contracts, sanitizer changes, and credential recovery. It does not supply a production Tuta read adapter. Do not mark the roadmap milestone complete or replace the app's sample mailbox with the default backend yet.

No live Tuta request was sent. No push or merge was performed. The reference checkout was not changed. No app, feature, or design-system source was changed.

## Commits

- `501286f feat(html): sanitize mail with blocked content` (requested cherry-pick, first commit)
- `ab7c741 fix(data): recover unreadable credential store`
- `363461f fix(html): retain safe mail presentation styles`
- `1bac519 feat(data): add offline mail read pipeline`
- `3cd9a9b fix(data): limit image consent to open views`
- A final documentation commit adds this handoff and the jsoup advisory result.

## Entry points

```kotlin
VelinBackend(context: Context, readSource: MailReadSource = UnavailableMailReadSource)
backend.auth: AuthRepository
backend.mailbox: MailboxRepository
backend.mail: MailRepository
```

The default source returns `Failure.Unsupported` for mailbox, paging, conversation, metadata, and attachments. Its body method returns `Failure.Decryption`. It never reports an empty successful mailbox or body. `MailReadSource` is a Velin injection boundary, not an upstream API. An authenticated SDK implementation must replace this default before live integration. Tests inject deterministic domain fixtures. They do not prove wire parsing or SDK decryption.

`BackendReadRepository(source, cacheDirectory)` implements both read repositories. It supports folder parent/depth validation, caller-supplied labels and senders, descending mail pages, cursor checks, duplicate removal, conversation grouping and order, single-message body loading, sanitizer integration, consent updates, inline bytes, and private attachment files. It preserves findings and attachment metadata supplied by the source. It does not derive them from Tuta entities.

1. A collector of `messages` requests the initial page with a null source cursor.
2. `loadMore` accepts only the current next cursor. A stale cursor returns `Failure.Protocol(409)`. A source that repeats the cursor returns `Failure.Protocol(502)`.
3. Conversation loading filters summaries to the requested conversation ID. It removes duplicate mail IDs. It loads bodies and sorts messages by sent time, oldest first.
4. A body failure remains `BodyState.Failed`. A decryption failure remains `BodyState.Failed(Failure.Decryption)`.
5. Downloads use random cache filenames. Sender names and entity IDs never become path components. Source metadata IDs and plaintext byte counts must match.
6. Mutation methods and local search return `Failure.Unsupported`. Recipient suggestions return an empty list. These workflows remain later milestones.

## New domain APIs

```kotlin
data class InlineImage(val bytes: ByteArray, val mimeType: String)
suspend fun MailRepository.allowExternalContent(account: AccountId, mailId: MailId): Outcome<Unit>
suspend fun MailRepository.inlineImage(account: AccountId, id: AttachmentId): Outcome<InlineImage>
```

Interface defaults return `Failure.Unsupported` to preserve existing implementations. The data repository implements both calls.

Consent requires an active conversation collector containing that message. It re-sanitizes the cached raw body and updates that conversation flow. Other messages keep their previous permission. Consent ends when the last collector of the conversation closes. A loaded message without a view returns `Failure.Protocol(409)` from the consent call. No sender trust is persisted.

The stable inline placeholder is `https://velin.invalid/inline/<zero-based index>`. The index belongs to one sanitized body. It is not a global attachment ID. `BodyState.Loaded.inlineImages` resolves it to an attachment ID by Content-ID. The renderer must intercept these URLs and reject an unresolved placeholder. It must never fetch them from the network.

Inline images accept `image/png`, `image/jpeg`, `image/gif`, and `image/webp`. MIME checks use the declared MIME type and matching magic bytes. The byte count must match attachment metadata. The maximum is 10 MiB. SVG is rejected. This validates the MIME signature, not every image structure or decoded pixel count. A future source must enforce download limits before allocating a whole remote file.

## Sanitizer

jsoup remains confined to `:core:html`, behind `MailHtmlSanitizer` and `SanitizedMailHtml`. No jsoup type escapes that module. Replacing its implementation does not require domain changes.

The sanitizer preserves a restricted set of inline presentation declarations. It rejects CSS escapes, comments, URL functions, image functions, variables, expression functions, imports, position properties, and unrecognized functions. It removes style elements and active content. CSS loads remain blocked even after image consent. This is deliberately stricter than upstream. A reviewed CSS parser would be needed to support more CSS grammar safely.

Upstream allows data images. Velin allows only bounded raster base64 data URLs. Remote image consent permits HTTP(S) image `src` values. It does not permit other load attributes or CSS loads. Tests include upstream-derived CSS attack strings, presentation styles, malformed markup, raster data images, and SVG rejection.

## Credentials

On AES-GCM authentication failure, the credential store deletes its unreadable atomic file. The failed read is reported once through the existing initialization error path. Later reads start empty. Save and remove recover from this failure and continue with an empty index. Other I/O, key access, format, and parsing errors are not silently discarded. Tests cover tampering and replacement of the encryption key.

## Threading and lifetime

Read source calls, sanitizer calls, and file operations run on `Dispatchers.IO`. One coroutine mutex serializes read operations and protects memory caches. Flow creation does not perform disk or network work. Cancellation propagates. Conversation cleanup uses a non-cancellable mutex section to clear view consent. Source methods must return typed failures and must not expose unauthenticated plaintext.

`BackendReadRepository.clear()` empties body and metadata caches, revokes consent, clears observed pages/conversations, and removes this pipeline's temporary attachment files. `VelinBackend` calls it on observed active-account changes and close. M4 must supply durable storage and account/session coordination for a production adapter. No Room or SQLCipher change was made.

## Source evidence

All paths below refer to `/home/ubuntu/src/tutanota`, commit `adccedea76c7049c78c9404332afda50f84bb05a`.

| Behavior or missing work | Upstream file and line |
|---|---|
| Mailbox membership and folder loading, not exported | `tuta-sdk/rust/sdk/src/mail_facade.rs:56`, `:73` |
| Exported single-mail entity loading | `tuta-sdk/rust/sdk/src/mail_facade.rs:132` |
| Internal typed range loading | `tuta-sdk/rust/sdk/src/crypto_entity_client.rs:104` |
| MailSet kind numbers | `tuta-sdk/rust/sdk/src/folder_system.rs:14` |
| MailSet parent and entries | `tuta-sdk/rust/sdk/src/entities/generated/tutanota.rs:837` |
| MailSetEntry uses a custom tuple ID and points to mail | `tuta-sdk/rust/sdk/src/entities/generated/tutanota.rs:3384` |
| Details and attachment session-key providers | `src/applications/common/api/worker/facades/lazy/MailFacade.ts:1277`, `:1293`, `:1318`, `:1335` |
| Received and draft details records | `tuta-sdk/rust/sdk/src/entities/generated/tutanota.rs:2999`, `:3034` |
| SDK bucket authentication is unfinished | `tuta-sdk/rust/sdk/src/crypto/crypto_facade.rs:288`, `:345` |
| Secure external branch panics | `tuta-sdk/rust/sdk/src/crypto/crypto_facade.rs:304` |
| Basic entity load constructs an entity REST URL | `tuta-sdk/rust/sdk/src/entity_client.rs:47` |
| CSS function restrictions and position removal | `src/applications/common/misc/HtmlSanitizer.ts:254`, `:314` |
| Data URL treatment | `src/applications/common/misc/HtmlSanitizer.ts:410`, `:637` |
| Upstream CSS test cases used by existing tests | `test/tests/misc/HtmlSanitizerTest.ts:175` in the sanitizer handoff; this path is absent from the supplied reference checkout, so these vectors are not independently traced here |
| Persisted per-sender AlwaysShow/AlwaysBlock rules | `src/applications/mail-app/mail/view/MailViewerViewModel.ts:588` |

Upstream persists explicit sender rules. Velin does not port that persistence in this branch.

## Verification and remaining work

`./gradlew testDebugUnitTest :core:domain:test assembleDebug :core:html:test --console=plain` passed on the final code. Gradle reported 292 tasks and completed in 16 seconds. `git diff --check` passed. The initial HTML check used an Android test task by mistake; the HTML module is JVM-only and its `test` task was then used. Unit tests exercise the memory pipeline, folder depths and cycles, entry cursor forwarding, stale cursors, conversation grouping/order, explicit decryption failures, consent updates/lifetime, image MIME/size rejection, and cache path safety. Credential tests cover unreadable-store recovery. Local code review was used. No child reviewer was launched because this task is itself an implementation subtask.

The only newly introduced production library is `org.jsoup:jsoup:1.23.2`, from the requested cherry-pick. Approval remains pending. No other new production dependency was added. OSV returned no advisory for that exact jsoup version; see `m3-jsoup-advisories.json`. This is not a full dependency audit. The earlier native dependency findings remain unresolved.

NOT IMPLEMENTED:

1. A production SDK read source and Velin-owned UniFFI overlay.
2. Tuta MailSet wire mapping, unread counter service, labels, aliases, and source pagination.
3. ConversationEntry traversal and wire-level grouping.
4. Received MailDetailsBlob loading with the parent-mail key provider.
5. Draft MailDetailsDraft key-provider integration.
6. Authenticated attachment metadata, blob download, and file decryption.
7. Wire findings mapping in the production read source.

These are implementation gaps, not only unverified live behavior. The memory pipeline must not be described as a working Tuta read client.

UNVERIFIED: live sign-in and read operations, real server cursor acceptance, real sender authentication, AndroidKeyStore behavior on device, WebView interception, device attachment rendering, and process restart. No test account exists.
