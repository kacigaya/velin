# M6 compose and send

M6 remains partial. Sender discovery and draft create, update, and reload are
implemented. Recipient resolution and send remain unsupported. No live Tuta
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

## Recipient resolution and send: NOT IMPLEMENTED

`sendDraft` returns `Outcome.Failed(Failure.Unsupported)`. Contact suggestions
remain an empty flow. No send request is constructed or sent.

The next blocker is verified public-key lookup. The pinned Rust
`tuta-sdk/rust/sdk/src/crypto/public_key_provider.rs:96` drops the public-key
signature. Its RSA version constraint does not replace identity verification.
The release's
`src/platform-kit/base/facades/lazy/KeyVerificationFacade.ts:75` requires the
public identity key, its trust source, and signature verification. The Rust
SDK has no matching PublicIdentityKeyProvider or identity trust database.
Using its current key loader for send would skip these checks.

Port and test that path before adding recipient resolution or send vectors.
Keep lookup absence, invalid input, and verification failure distinct.
Then generate SendDraftData vectors with signed test keys before implementing
confidential internal send. Sending to the owner's own address still needs
this verification path. Do not add an unencrypted fallback.

The later paths remain external send, reply creation, encrypted upload,
scheduled send, and password-protected external send. No new domain API for
those operations was added.

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
8. Send is unsupported. After verified internal send is implemented and
   merged, any separately authorized send check must target only the owner's
   own address. No live send check is authorized by this implementation.

## Offline verification

The final native build passed for both Android ABIs and the host. All 33
selected Rust tests and 139 JVM tests passed. The full 296-task Gradle
command, including assembleDebug, passed. Eight upstream draft vectors and
one ID-only response vector passed. See
[DRAFT-ENCRYPTION.md](DRAFT-ENCRYPTION.md#final-offline-verification) for the
commands, test scope, artifact hashes, warnings, and dependency scan limits.
