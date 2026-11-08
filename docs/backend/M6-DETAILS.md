# M6 compose and send

M6 is incomplete. Only sender discovery is implemented here. No live Tuta
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

## Draft and send limits: NOT IMPLEMENTED

`saveDraft` and `sendDraft` still return `Outcome.Failed(Failure.Unsupported)`.
Contact suggestions remain an empty flow. There is no domain API for recipient
resolution, draft deletion, reply creation, uploads, or scheduled-send cancellation.
No content is sent through an unencrypted fallback.

Implement the remaining paths in this order:

1. Draft create, update, reload, and delete. Repeated saves must use the returned
   draft ID. Plain text needs the release HTML conversion. Retain existing files
   and reply-to fields during update.
2. Recipient resolution through verified public-key lookup. Keep unknown,
   invalid, and verification-failure states distinct.
3. Confidential internal send with the release bucket-key construction,
   TutaCrypt and RSA selection, and KeyVerificationFacade checks.
4. Non-confidential external send.
5. Reply, reply-all, and forward, with conversation linkage and quoted content.
6. Encrypted attachment upload and retention during update and forward.
7. Scheduled send and cancellation.
8. Password-protected external send. Return `Failure.Unsupported` until the
   secure-external path is complete. Never retry as non-confidential mail.

The first missing protocol layer is nested service encryption:

1. [Draft creation](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L332)
   builds MailTransferAggregatedType and MailDetailsBlobTransferAggregatedType.
   Both carry the same owner-wrapped mail session key. Deprecated fields stay null.
2. [Draft update](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L505)
   reuses the draft key, key version, and nonce. It preserves reply-to fields and
   computes attachment differences. It waits for the updated mail before send.
3. [Service encryption parameters](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/base/base-crypto/KeyLoaderFacade.ts#L281)
   select historical keys for update and current keys for creation.
4. [The instance pipeline](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/instance-pipeline/InstancePipeline.ts#L69)
   chooses AES-CBC or AEAD from the user's encryption scheme. WithSessionKey
   must match the nested transfer encryption. The Rust executor's single
   `session_key` parameter does not supply this pipeline. Port and test it before
   exposing draft writes. Do not force AES-CBC for an AEAD account.
5. [DraftService](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/entities/tutanota/Services.ts#L118)
   has POST and PUT only. Do not invent a DraftService DELETE operation.

Proposed reply API, not yet added to the domain:

```kotlin
enum class DraftMode { Reply, ReplyAll, Forward }
suspend fun draftFor(account: AccountId, mode: DraftMode, mailId: MailId): Outcome<Draft>
```

Before adding this API, extend Draft to retain the source message and conversation
metadata across saves and process death. No domain signature changed in this work.

## Owner live checks: UNVERIFIED

1. Sign in and sync. Compare the primary address and enabled aliases with the
   official client. Check that disabled aliases are absent.
2. Set different sender names for two aliases in the official client. Sync Velin.
   Check both names. Include a shared mailbox if the account has one.
3. Change an alias in the official client. Sync again. Check the new snapshot.
4. Sign out, then switch accounts. Check that aliases do not cross sessions.
5. Do not test draft save or send from this implementation. Those paths remain
   unsupported. After they are implemented and merged, send only to the owner's
   own address under the owner's separate live-test authorization.

## Offline verification

1. The pinned native build passed for arm64-v8a, x86_64, and the host. A final
   incremental arm64 build used the release Cargo configuration and final source.
   `m6-artifacts.sha256` records the three final libraries. Binding generation
   passed. Optional ktlint formatting was unavailable. Existing Rust warnings remain.
2. `tools/backend/test-read-overlay.sh` passed all 19 matching Rust tests.
   Three cover sender ownership, enabled aliases and names, and invalid or failed
   GroupInfo reads. The filter excluded 304 other unit tests and all integration
   tests. No live service test ran.
3. `./gradlew testDebugUnitTest :core:domain:test :core:html:test assembleDebug --console=plain --max-workers=2`
   passed all 295 tasks. JVM reports contain 127 tests with no failures, errors,
   or skipped tests. The added data test checks alias mapping, failure
   propagation, and the session boundary.
4. The release pin check, Bash syntax check, and `git diff --check` passed.
   Local review covered the changed source, callers, contracts, and tests.
5. OSV results for all 327 public Rust packages exactly match
   `release-native-advisories.json`: 17 affected packages and 35 advisory records.
   The recorded 14-package Maven set has no advisory. The unchanged Rust risks
   remain. No dependency update was made.

These checks verify local construction and mapping. Server acceptance and device
behavior remain UNVERIFIED. No complete numbered M6 slice is delivered yet.
