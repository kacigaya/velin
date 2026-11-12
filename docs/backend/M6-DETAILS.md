# M6 compose and send

M6 remains partial. Sender discovery and draft create, update, and reload are
implemented. M6b adds recipient resolution, persistent identity trust, and
confidential internal send. See [the M6b report](M6B-REPORT.md). No live Tuta
request was sent. No production dependency was added.

## Sender aliases

`MailboxRepository.senders` keeps its domain signature:

```kotlin
fun senders(account: AccountId): Flow<List<SenderAlias>>
```

The SDK boundary adds:

```kotlin
suspend fun senders(): List<ReadAddress>
```

1. Read the active user's mail-group memberships. For the user's mailbox,
   read addresses from the user GroupInfo. For a shared mailbox, read its
   GroupInfo. Exclude groups owned by another user.
2. Return enabled aliases and the primary address. Remove duplicate addresses.
   Every returned `SenderAlias` has `enabled = true`.
3. Read sender names from MailboxProperties. Use the legacy user GroupInfo
   name when an address has no saved name. Do not create properties during a read.
4. Reject mismatched entity IDs and entity or nested property decryption errors.
   Use the existing session boundary. Keep no new cache of aliases.
5. Aliases refresh through the existing mailbox sync. Event-driven alias updates
   remain part of M4.

Release evidence:

1. [Address ownership](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/tuta-sdk/rust/sdk/src/mail_facade.rs#L139)
   and [enabled addresses](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/tuta-sdk/rust/sdk/src/mail_facade.rs#L230).
2. [User versus shared addresses](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/mailFunctionality/SharedMailUtils.ts#L84).
3. [Per-address names](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/misc/MailboxPropertiesUtils.ts#L16)
   and [legacy name migration](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/mailFunctionality/MailboxModel.ts#L214).

## Draft contracts

`MailRepository` adds:

```kotlin
suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft>
```

These signatures stay the same:

```kotlin
suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId>
suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit>
```

`Draft` adds a final optional property:

```kotlin
val htmlBody: String? = null
```

1. Save a new draft with `id = null`. Keep the returned DraftId. Use it for
   every later save. Create uses DraftService POST. Update uses PUT with the
   same mail ID. There is no DraftService DELETE operation.
2. Create wraps a new mail session key with the current mail-group key.
   Update uses the draft's historical owner-key version. It retains the
   wrapped key, nonce, method, and reply-to fields. Both nested transfer roots
   carry the same wrapped mail session key.
3. The authenticated AEAD rollout selects the account's encryption scheme.
   Unknown state blocks writes. An AEAD account never falls back to CBC.
4. Plain text uses the release converter. Reload returns a plain editor view
   and the original HTML. A subject-only edit keeps that HTML. A body edit
   converts the new plain text to HTML.
5. Update retains the supplied existing attachment IDs. It removes omitted
   IDs. It rejects new files until encrypted upload exists. Scheduled drafts
   and moves between sender mail groups remain unsupported.
6. After POST or PUT, read the draft until its content matches. The limit is
   20 reads, with 250 ms between reads. A timeout, cancellation, or lost
   response can follow a successful server write. There is no automatic
   write retry. Check the official client before retrying a failed create.
7. `loadDraft` reads fresh authenticated data. Recipient types remain Unknown.
   It does not infer a verified recipient from a mail address. Saved drafts
   use the existing mailbox read path, including session-key AEAD decryption.
8. Discard uses the existing move-to-Trash action. No UI module was changed.
   Known cached pages refresh on the next sync.

Release evidence:

1. [Draft create](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L341)
   and [draft update](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L505).
2. [Current and historical service keys](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/base/base-crypto/KeyLoaderFacade.ts#L281).
3. [Scheme selection](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/instance-pipeline/InstancePipeline.ts#L69)
   and [the AEAD rollout](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/EventBusEventCoordinator.ts#L162).
4. [Plain-text conversion](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/common/utils/imapImportUtils/PlainTextToHtmlConverter.ts#L1).

See [draft encryption and vectors](DRAFT-ENCRYPTION.md) for offline evidence.
Server acceptance and device behavior remain UNVERIFIED.

## Recipient resolution and send

M6b implements verified recipient lookup and confidential internal send.
See [contracts, release evidence, limits, and live checks](M6B-REPORT.md).
External send, upload, and scheduling remain unsupported.

## Reply and forward

`MailRepository.prepareResponse(account, previous, kind)` builds an unsaved
draft for `Reply`, `ReplyAll` or `Forward`. It follows
`MailViewerViewModel.ts:1115` and `:1163` at the pinned release.

1. A reply to received mail goes to the reply-to addresses, or to the sender
   when there are none. Reply all adds the To and Cc recipients as Cc, except
   the user's enabled addresses. A reply to sent mail goes to its recipients
   again. Reply all then keeps its Cc and Bcc. Each address shows once.
2. The mail state decides the direction, as upstream does: `MailState` 2 is
   received. `prepareResponse` reads it fresh from the server, because mail
   saved offline by v0.3 has no state.
3. The sender is the enabled address that received the mail. Sent mail uses
   its own sender. Otherwise the first enabled address is used.
4. A reply subject gets `Re: ` once. A forward subject gets `FWD: `, as
   upstream does. A forward starts with no recipients.
5. The composer edits plain text. The quote is the previous body as `>` lines,
   with one more level than the previous quotes. `DraftHtml.fromPlainText`
   turns them into nested blockquotes. A safe link keeps its target after the
   label, for example `Join (https://example.com)`. A forward adds a Date,
   From, To, Cc and Subject header above the quote. The header words are English.
6. `Draft.response` carries the previous mail. `velin_save_draft` uses it only
   when it creates the draft. It loads the previous mail and its conversation
   entry, and sends `previousMessageId` and `conversationType` (1 reply,
   2 forward) in DraftCreateData. A deleted mail or entry sends no message ID,
   as upstream does. The server then threads the sent mail.
7. `velin_send_draft` accepts conversation types 1 and 2. Type 0 with a
   previous entry and unknown types stay unsupported.
8. An untouched response closes without a draft. The first edit creates it.
   A failed body load fails the response, so a quote is never silently empty.

Live check on 2026-11-12, Android 15 in a container, against production:

1. Reply to a self-sent internal mail prefilled the sender, Re subject and
   quote. The sent reply joined the same conversation, with sent and received
   copies.
2. Reply to an external mail prefilled the external sender. It was not sent.
3. Forward prefilled FWD subject, no recipients and the header. Closing the
   untouched reply and forward left no draft.

Limits:

1. Forwarding does not copy attachments. Attachment upload is not built yet.
   The composer tells the user how many files it left out.
2. Velin does not set the replied or forwarded state on the previous mail.
   Upstream updates the Mail entity after send. The Rust SDK has no encrypted
   entity update yet.
3. Inline images in the quote are not carried. The quote keeps text only.

## Owner live checks: UNVERIFIED

Run only after merge and separate owner authorization.

1. Sign in and sync. Compare enabled aliases and sender names with the
   official client. Check that disabled aliases are absent.
2. In the owner's mailbox, create a draft with a subject, Unicode text,
   escaped characters, blank lines, and quoted lines. Check the result in the
   official client. Do not send it.
3. Change the subject, body, sender alias within the same mailbox, and the
   To, Cc, and Bcc fields. Save more than once. Check that one draft keeps
   the same ID and contains the last saved content.
4. Close and reopen the draft through `loadDraft`. Restart the app and reopen
   it again. Check every recipient list and the body. Change only the subject
   of a draft with rich HTML. Check that the original body format stays intact.
5. Create a draft with an existing attachment and reply-to field in the
   official client. Reopen it in Velin. Save it. Check that retained files and
   reply-to fields remain. Remove one attachment and check that removal.
6. Check both legacy CBC and AEAD accounts when available. Do not change the
   rollout or force a scheme for a test. Unknown rollout state must block save.
7. Sign out and switch accounts. Check that aliases, drafts, and encryption
   state do not cross sessions. Check offline and cancelled saves. Reconcile
   a failed create with the official client before retrying it.
8. M6b send checks must follow [the self-send instructions](M6B-REPORT.md#owner-live-check).
   No live send check is authorized by this implementation.

## Offline verification

The final native build passed for both Android ABIs and the host. All 33
selected Rust tests and 139 JVM tests passed. The full 296-task Gradle
command, including assembleDebug, passed. Eight upstream draft vectors and
one ID-only response vector passed. See
[DRAFT-ENCRYPTION.md](DRAFT-ENCRYPTION.md#final-offline-verification) for the
commands, test scope, artifact hashes, warnings, and dependency scan limits.
