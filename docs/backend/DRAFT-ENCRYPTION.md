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
