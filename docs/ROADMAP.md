# Velin roadmap

Each milestone is small and can be tested alone. A milestone is complete when its exit checks pass. Status values: **done**, **in progress**, **planned**, **blocked**.

## Milestones

| # | Milestone | Owner | Status | Exit checks |
|---|---|---|---|---|
| M0 | Project scaffold, theme, UI screens with sample data | UI | done | Debug APK builds. Unit and screenshot tests pass in light and dark. |
| M1 | Native core: Rust SDK AAR builds for arm64 and x86_64, `:core:sdk` adapter, REST transport with `cv`/`v` headers | Backend | done | AAR builds from a pinned upstream commit with checksums. Adapter unit tests pass. |
| M2 | Sign-in: salt, Argon2id and bcrypt KDF, session creation, TOTP, Keystore credential storage, session resume | Backend, then UI wiring | done, verified live and on a device 2026-11-08 | Upstream KDF test vectors pass. Live sign-in with a test account. |
| M3 | Read path: folders, mail list paging, decryption, mail body, HTML sanitizer, attachment download | Backend, then UI wiring | in progress: folders, labels, inbox paging and received mail bodies verified live 2026-11-08; attachment list and download verified live 2026-11-12 (PDF from external mail, after a restart); unread counters verified live 2026-11-13 (account switch and sign-out not checked) | Live list and body load. Sanitizer tests pass. |
| M4 | Sync and offline: event bus WebSocket, encrypted offline store, durable cursors | Backend | done, verified live on a device 2026-11-12: offline cold start shows saved mail, and a move from a second client replays after reconnect (see [M4-SYNC.md](backend/M4-SYNC.md)) | Offline cold start shows saved mail. Missed events replay after reconnect. |
| M5 | Mail actions: read state, move, archive, trash, labels, spam report | Backend, then UI wiring | in progress: read state, moves with undo and labels verified live 2026-11-08; permanent delete from Trash verified live 2026-11-12; spam and phishing reports built 2026-11-12, with consent each time | Actions reflect on the server and in a second client. |
| M6 | Compose and send: drafts, attachment upload, internal confidential, external non-confidential, scheduled send | Backend, then UI wiring | in progress: sender aliases and drafts done; draft create, update in place, reload and discard to Trash verified live 2026-11-09; recipient lookup with key trust and confidential send to a Tuta address verified live 2026-11-09 (self-send); reply, reply all and forward done, reply send verified live 2026-11-12 (self-send, threaded); the previous mail is marked replied or forwarded after send with an encrypted PATCH, verified live 2026-11-12 (self-reply; the official app opens the patched mail); recipient key check by hand and persistent fingerprint rejection done 2026-11-12; external send, attachments and scheduled send not implemented | Mail arrives at a Tuta and a non-Tuta address. Drafts survive process death. |
| M7 | Notifications: SSE service, notification UI, quick actions | Backend, then UI | planned | New mail notifies with the app in the background. |
| M8 | Search, contact suggestions, settings (signatures, aliases, inbox rules), multi-account | Both | planned | Feature parity rows below are checked. |
| M9 | Hardening: security review, performance, accessibility audit, parity review against the official app | Both | planned | No open high-severity findings. |

## Release plan to v1.0.0

v1.0.0 means: you can use Velin every day in place of the official Tuta Android app for normal mail. It shows sender and recipient trust correctly. It has no open high-severity findings.

| Release | Theme | Scope | Exit check |
|---|---|---|---|
| 0.5.0-alpha | Trust and finish reading | Check the sender key of received TutaCrypt mail against the pinned identity, as upstream does, and warn when the check fails. Velin shows no "verified" mark, so a trust-on-first-use result is not over-trusted. Screen to check a recipient's key by hand. Spam and phishing reports. Encrypted entity update in Rust, then the replied and forwarded state. Live check of unread counters. | M3 and M5 done. A mail with a mismatched sender key shows a warning. |
| 0.6.0-alpha | Attachments out | Encrypted attachment upload. Attach files in compose. Forward with the original files. Inline images in quotes. | A file sent from Velin opens in the official client. |
| 0.7.0-alpha | Mail to everyone | Send to external addresses without and with a password (port from TS). Scheduled send and undo send. | M6 done. Mail arrives at a Tuta and a non-Tuta address. |
| 0.8.0-beta | Notifications | Background push service, new-mail notifications, quick actions. Releases are beta from here: Velin is usable every day. | M7 done. New mail notifies with the app in the background. |
| 0.9.0-beta | Search and contacts | Replace the encrypted file store with SQLCipher and migrate saved data. Local search index. Contact suggestions. SQLCipher is a new production dependency and needs owner approval. | Search finds mail offline. An update from 0.8 keeps saved mail. |
| 0.10.0-beta | Settings and accounts | Signatures, alias and sender names, inbox rules, multi-account. | M8 done. All feature parity rows are checked. |
| 1.0.0-rc.1 | Hardening | External security review, performance and battery check, accessibility audit, parity review. Updates tested from every earlier release. A written process to follow upstream client versions (HTTP 474). | M9 done. No open high-severity findings. |
| 1.0.0 | Release | Fixes from the rc phase only. | The rc ran for two weeks or more with no new high-severity bug. |

Order: trust comes before new features. Upload comes before external send, because external mail with files uses it. The store migration gets its own release because it has the highest risk of data loss.

Out of scope for 1.0: security key sign-in (blocked upstream, see risk 4) and rich-text editing (risk 8). Every release checks the SDK pin, the dependency advisories and a live device pass before the tag.

## Blockers and risks

1. **Live checks.** The owner provided a test account. Sign-in, session revoke, folders, labels, inbox paging and received mail bodies work against production. The signed v0.1.0-alpha release APK signs in and runs on a real device. On 2026-11-09 the debug APK passed a full pass on Android 15 in a container (phone and tablet layouts): sign-in, session resume, read, unread, archive with undo, labels, drafts and confidential send to a Tuta address. See [DEVICE-TESTING.md](DEVICE-TESTING.md). Pin Velin to a published Tuta release, never master: master can be ahead of the deployed models and the server then rejects requests with HTTP 400.
2. **Rust SDK gaps.** `create_session` is not production ready. bcrypt key derivation panics. The release SDK authenticates a TutaCrypt sender only against the server's key. Velin's overlay also checks that key against the pinned identity (2026-11-12). External confidential mail is unimplemented in Rust. Velin must fix or port these before it claims authenticated reading or external confidential sending.
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
| Folders and labels list | Done, verified live, unread counts too | done, wired |
| Mail list, paging, unread state | Done, verified live | done, wired |
| Conversation view | Port from TS | done (sample data) |
| Mail body and attachments | Body and attachment download verified live | done, wired; attachments open in another app |
| Remote content blocking | Sanitizer and consent done | done, wired |
| Phishing and authentication banners | Data from server and TS logic | done (sample data) |
| Read state, archive, trash | Verified live | done, wired |
| Move, labels, spam report | Move and labels verified live; spam and phishing reports send the mail key after consent | done, wired |
| Compose, reply, forward | Confidential send to Tuta addresses verified live; reply verified live, forward built without attachments; replied and forwarded state set on the original mail | composer wired to drafts, send, reply and forward |
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
