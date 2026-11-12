# Velin roadmap

Each milestone is small and can be tested alone. A milestone is complete when its exit checks pass. Status values: **done**, **in progress**, **planned**, **blocked**.

## Milestones

| # | Milestone | Owner | Status | Exit checks |
|---|---|---|---|---|
| M0 | Project scaffold, theme, UI screens with sample data | UI | done | Debug APK builds. Unit and screenshot tests pass in light and dark. |
| M1 | Native core: Rust SDK AAR builds for arm64 and x86_64, `:core:sdk` adapter, REST transport with `cv`/`v` headers | Backend | done | AAR builds from a pinned upstream commit with checksums. Adapter unit tests pass. |
| M2 | Sign-in: salt, Argon2id and bcrypt KDF, session creation, TOTP, Keystore credential storage, session resume | Backend, then UI wiring | done, verified live and on a device 2026-11-08 | Upstream KDF test vectors pass. Live sign-in with a test account. |
| M3 | Read path: folders, mail list paging, decryption, mail body, HTML sanitizer, attachment download | Backend, then UI wiring | in progress: folders, labels, inbox paging and received mail bodies verified live 2026-11-08; attachments and unread counters not yet verified live | Live list and body load. Sanitizer tests pass. |
| M4 | Sync and offline: event bus WebSocket, encrypted offline store, durable cursors | Backend | done, verified live on a device 2026-11-12: offline cold start shows saved mail, and a move from a second client replays after reconnect (see [M4-SYNC.md](backend/M4-SYNC.md)) | Offline cold start shows saved mail. Missed events replay after reconnect. |
| M5 | Mail actions: read state, move, archive, trash, labels, spam report | Backend, then UI wiring | in progress: read state, moves with undo and labels verified live 2026-11-08; permanent delete from Trash verified live 2026-11-12; spam/phishing reports not implemented | Actions reflect on the server and in a second client. |
| M6 | Compose and send: drafts, attachment upload, internal confidential, external non-confidential, scheduled send | Backend, then UI wiring | in progress: sender aliases and drafts done; draft create, update in place, reload and discard to Trash verified live 2026-11-09; recipient lookup with key trust and confidential send to a Tuta address verified live 2026-11-09 (self-send); reply, reply all and forward done, reply send verified live 2026-11-12 (self-send, threaded); external send, attachments and scheduled send not implemented | Mail arrives at a Tuta and a non-Tuta address. Drafts survive process death. |
| M7 | Notifications: SSE service, notification UI, quick actions | Backend, then UI | planned | New mail notifies with the app in the background. |
| M8 | Search, contact suggestions, settings (signatures, aliases, inbox rules), multi-account | Both | planned | Feature parity rows below are checked. |
| M9 | Hardening: security review, performance, accessibility audit, parity review against the official app | Both | planned | No open high-severity findings. |

## Blockers and risks

1. **Live checks.** The owner provided a test account. Sign-in, session revoke, folders, labels, inbox paging and received mail bodies work against production. The signed v0.1.0-alpha release APK signs in and runs on a real device. On 2026-11-09 the debug APK passed a full pass on Android 15 in a container (phone and tablet layouts): sign-in, session resume, read, unread, archive with undo, labels, drafts and confidential send to a Tuta address. See [DEVICE-TESTING.md](DEVICE-TESTING.md). Pin Velin to a published Tuta release, never master: master can be ahead of the deployed models and the server then rejects requests with HTTP 400.
2. **Rust SDK gaps.** `create_session` is not production ready. bcrypt key derivation panics. The mail bucket-key path has no sender authentication. External confidential mail is unimplemented in Rust. Velin must fix or port these before it claims authenticated reading or external confidential sending.
3. **Native build on aarch64.** Solved: host clang and lld build the SDK against NDK sysroots (`tools/backend/build-sdk.sh`). The APK ships arm64-v8a and x86_64 only.
4. **Security keys (WebAuthn/U2F).** Upstream uses a browser callback bound to its own app. Server acceptance of Velin's package is unverified. Hardware-key-only accounts can be blocked.
5. **Client version enforcement.** The server rejects old clients with HTTP 474. The policy for a third-party client is unverified. Velin must track upstream versions (see research §7).
6. **Rust dependency advisories.** 13 pinned crates have advisories. `rsa 0.9.8` (RUSTSEC-2023-0071, timing side channel) has no fixed version. Upstream ships the same pin. See `docs/backend/dependency-advisories.json`.
7. **jsoup.** Approved by the owner on 2026-11-08 for the mail HTML sanitizer. It stays inside `:core:html`.
8. **Rich-text editing.** Compose has no rich-text editor. The composer body is plain text in v1.

## Feature parity checklist

Source status comes from research §6. "UI" shows the Velin screen state.

| Feature | Backend status | UI |
|---|---|---|
| Password sign-in | Done, verified live | done, wired |
| TOTP and other-session approval | Done, live check pending | done, wired |
| Security key sign-in | Blocked | entry point shows "not supported" |
| Session resume, saved accounts | Done (Keystore AES-GCM) | done, wired |
| Multi-account | Port from TS | planned |
| Folders and labels list | Done, verified live (unread counts need the event bus) | done, wired |
| Mail list, paging, unread state | Done, verified live | done, wired |
| Conversation view | Port from TS | done (sample data) |
| Mail body and attachments | Port from TS | done (sample data) |
| Remote content blocking | Sanitizer and consent done | done, wired |
| Phishing and authentication banners | Data from server and TS logic | done (sample data) |
| Read state, archive, trash | Verified live | done, wired |
| Move, labels, spam report | Move and labels verified live; spam report sends no report yet | done, wired |
| Compose, reply, forward | Confidential send to Tuta addresses verified live; reply verified live, forward built without attachments | composer wired to drafts, send, reply and forward |
| Drafts | Done, verified live | done, wired |
| External confidential mail | Blocked in Rust core | password fields done |
| Scheduled send, undo send | Port from TS | entry points done |
| Search | Port from TS (local index) | planned |
| Contact suggestions | Port from TS | suggestion list done |
| Notifications | Native reuse | planned |
| Offline mode | Encrypted file store and event replay, verified live | offline banner done, wired |
| Inbox rules | Port from TS | planned |
| Aliases and signatures | Port from TS | sender picker done |
| Settings | Port from TS | planned |
