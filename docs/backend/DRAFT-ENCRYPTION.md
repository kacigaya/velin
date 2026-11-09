# Draft encryption

The protocol source is release commit
`332e57461cdc4fe4a4ad9bdc8555f56df02931c0`.
See [the vector instructions](../../tools/backend/vectors/README.md).

## Model and account state

The overlay retains `targetTypeId` and `transferredAttributeId` from the
release models. AEAD needs these IDs to authenticate the persistent field
path rather than its temporary service path. Fields keep their numeric types.

Each native UserFacade starts with an unknown encryption scheme.
Before the first draft write, MailFacade loads the authenticated
`sys/rolloutservice` GET response. A failed load leaves the state unknown
and blocks the write. Rollout type 5 selects AEAD. Its absence selects CBC,
as in `src/platform-kit/base/facades/RolloutFacade.ts:23` and
`src/applications/common/api/worker/EventBusEventCoordinator.ts:162`.
The state belongs to the login session and cannot downgrade after AEAD.
No new Kotlin contract or production dependency is needed for this state.

The Rust checks cover metadata retention, unknown state, AEAD selection,
separate sessions, failed rollout loads, and reuse of a loaded scheme.

## Nested encryption

`VelinSessionTransferEncryption` implements only `WithSessionKey`.
It unwraps the session key at each transfer root. Nested fields use the
persistent target type and transferred attribute IDs from the model.
AEAD authenticates each aggregate ID in its path. MailDetailsDraft uses
MailDetailsBlob's canonical context, as in
`src/platform-kit/instance-pipeline/CanonicalId.ts:5`.

The draft serializer sorts numeric field IDs and preserves every value.
The eight complete JSON strings match the pinned TypeScript output byte
for byte. Both schemes pass round trips. Wrong owner keys, missing wrapped
keys, wrong session keys, and altered AEAD tags fail closed.

Draft compression uses a port of
`src/platform-kit/instance-pipeline/Compression.ts:142`. The SDK's existing
LZ4 encoder emits valid but different bytes for repeated text. The port
keeps the source license. Decompression uses the existing bounded decoder.
No cryptographic primitive or production dependency was added.

`WithInstanceKey` is outside this draft path. It must not fall back to CBC.

## Draft writes and reload

The native API keeps keys inside Rust. Kotlin supplies `WriteDraft` and
receives a `ReadId`. The domain API adds
`suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft>`.
The existing save signature returns the ID used for later updates.

`Draft.htmlBody: String? = null` retains source HTML across reloads. If the
plain text is unchanged, save keeps this HTML. Otherwise it uses the release
plain-text converter. Twelve generated cases cover escaping, CRLF, blank
lines, quote levels, and Unicode. The plain editor conversion uses an
iterative DOM walk, including for deeply nested input.

Readback after a write compares subject, sender, recipients, body,
confidential flag, and retained attachments. It does not retry the write.
This is a bounded read substitute for the release's event wait. Durable event
replay remains M4. A failed response can follow a successful server write.

The offline tests also remap encrypted transfer fields into the persistent
Mail and MailDetailsDraft models. They check the canonical draft context
through the same EntityFacade used by mailbox reads. This does not prove
server acceptance of a write.

No app, feature, or design-system module was changed. Upload, scheduling,
cross-mailbox sender changes, recipient resolution, and send remain unsupported.
`WithInstanceKey` records remain outside this session-key implementation.
Such records fail closed. They never trigger a CBC write fallback.

The vectors cover 128-bit and 256-bit owner keys. New drafts always use a
256-bit session key. Updates also cover historical 128-bit session keys,
including after the AEAD rollout. The release's KDF converts those keys into
256-bit AEAD subkeys. The SDK adapter uses the existing BLAKE3 KDF and AEAD
primitives. It does not force CBC for those accounts.

Draft update also decodes the release's ID-only DraftUpdateReturn. Its old
encrypted type marker remains true, but its model has no encrypted fields.
An upstream response vector proves the keyless decode. A model check rejects
new ciphertext or aggregate fields before using this case. Crypto failures
never trigger a plaintext retry. Historical null owner-key versions also
stay null in the update transfer data; only key lookup interprets null as 0.

## Kotlin boundary

The domain and source boundary use these signatures:

```kotlin
suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft>
suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId>
```

The save signature already exists on MailRepository. MailReadSource now
supports both methods. Draft adds `val htmlBody: String? = null` as its last
constructor property. Existing constructor calls keep their meaning.

SdkMailReader adds:

```kotlin
suspend fun saveDraft(draft: WriteDraft): ReadId
suspend fun loadDraft(id: ReadId): ReadMessage
```

Its new input record is:

```kotlin
data class WriteDraft(
    val id: ReadId?,
    val sender: ReadAddress,
    val to: List<ReadAddress>,
    val cc: List<ReadAddress>,
    val bcc: List<ReadAddress>,
    val subject: String,
    val html: String,
    val confidential: Boolean,
    val attachments: List<ReadId>,
)
```

`SdkFailure.DraftUnsupported` maps to `Failure.Unsupported`. No key material
or generated binding type enters a domain contract.

## Final offline verification

1. The native release build passed for arm64-v8a, x86_64, and the host.
   Binding generation passed. Optional ktlint formatting was unavailable.
   `m6-draft-artifacts.sha256` records the final libraries.
2. `CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0 tools/backend/test-read-overlay.sh`
   passed all 33 selected Rust tests. The selector excluded 304 other unit
   tests and all integration tests. Debug symbols and incremental compilation
   were disabled for this test build. Runtime assertions remained enabled.
3. `./gradlew testDebugUnitTest :core:domain:test :core:html:test assembleDebug --console=plain --max-workers=2`
   passed all 296 tasks. Reports contain 139 JVM tests with no failures,
   errors, or skipped tests. SDK tests ran against the final host library.
   That library is now a declared test input.
4. Final vector regeneration produced identical JSON and HTML test resources.
   Pin checks, overlay syntax checks, Bash syntax, Node syntax, and
   `git diff --check` passed. Local review covered callers, key selection,
   response decoding, cancellation, error mapping, and cache invalidation.
5. The OSV scan covered 327 exact public Rust packages. The same 17 packages
   and 35 advisory IDs remain affected. The RSA advisory now lists 0.10.0 as
   a fixed version; the pinned 0.9.8 remains affected. The recorded report was
   refreshed. No dependency was changed. Fifteen checked Maven packages,
   including jsoup, have no reported advisory. Existing release blockers remain.
6. No live Tuta request ran. Server acceptance, device behavior, and the
   owner checks in M6-DETAILS remain unverified. Verified recipient lookup,
   identity trust, send, and SendDraftData vectors are not implemented.
