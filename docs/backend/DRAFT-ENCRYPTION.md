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
The four complete JSON strings match the pinned TypeScript output byte
for byte. Both schemes pass round trips. Wrong owner keys, missing wrapped
keys, wrong session keys, and altered AEAD tags fail closed.

Draft compression uses a port of
`src/platform-kit/instance-pipeline/Compression.ts:142`. The SDK's existing
LZ4 encoder emits valid but different bytes for repeated text. The port
keeps the source license. Decompression uses the existing bounded decoder.
No cryptographic primitive or production dependency was added.

`WithInstanceKey` is outside this draft path. It must not fall back to CBC.
