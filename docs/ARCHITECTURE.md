# Velin architecture

Velin is a native Android mail client for Tuta. It uses Kotlin, Jetpack Compose, and Material 3 Expressive. This document describes the module layout, the ownership rules, and the decisions behind them.

Backend facts come from [research/backend-feasibility.md](research/backend-feasibility.md). That report cites upstream source at commit `adccedea76c7049c78c9404332afda50f84bb05a`.

## Approach

Velin uses a **hybrid** core:

- Rust SDK code from upstream for cryptography and typed entity handling. Velin adds the UniFFI exports it needs.
- Kotlin for authentication, sync, mail workflows, offline storage, search, and notifications. Upstream implements these in TypeScript, and the SDK does not export them.

The official Android app is a WebView shell around the TypeScript client. Its native parts (SSE push, Keystore credential storage, SQLCipher, file crypto) are reuse candidates under GPLv3.

## Modules

| Module | Owner | Responsibility |
|---|---|---|
| `:app` | UI | Activity, theme root, navigation, adaptive shell, DI wiring. |
| `:core:designsystem` | UI | Theme, color schemes, icons, shared components. |
| `:feature:auth` | UI | Sign-in, two-step verification, saved accounts. |
| `:feature:mail` | UI | Mailbox list, folder pane, conversation reader, composer, ViewModels. |
| `:core:domain` | Backend | Domain models and repository interfaces. Pure Kotlin. |
| `:core:sdk` | Backend | Pinned Rust SDK AAR, Velin binding extensions, typed adapter. |
| `:core:network` | Backend | HTTP, required headers, WebSocket event bus, protocol errors. |
| `:core:database` | Backend | Planned for M8: SQLCipher with a search index. Until then, `:core:data` keeps one encrypted file per account with sync cursors ([M4-SYNC.md](backend/M4-SYNC.md)). |
| `:core:data` | Backend | Repository implementations, mail workflows, offline reconciliation, search. |
| `:core:notifications` | Backend | SSE connection, background lifecycle, notification data. |

Dependency direction: `feature → domain ← data → (sdk, network, database)`. Feature modules never depend on data modules. `:app` wires implementations to interfaces.

## Ownership rule

UI work and backend work have separate owners. UI covers screens, layouts, themes, colors, typography, motion, and navigation. Backend covers protocols, crypto, persistence, sync, notifications, and tests for them. Backend changes never touch UI modules. UI changes never touch backend modules.

## UI layer

- **Screens are stateless.** Each screen takes a `UiState` and one `onEvent` callback. ViewModels map domain models to UI models (`feature/mail/model`). Screens never see Tuta entity types.
- **Theme.** `MaterialExpressiveTheme` with `MotionScheme.expressive()`. Dynamic color on Android 12 and later. Fallback schemes come from `tools/gen_color_schemes.py` for standard, medium, and high contrast. Android 14 and later select them from the system contrast setting.
- **Adaptive layout.** Below 840dp, folders open in a modal drawer. At 840dp and wider, the folder pane stays visible. Mail list and conversation use Navigation 3 list-detail scenes.
- **Icons.** Material Symbols Rounded as vector drawables (Apache 2.0). Velin does not use the frozen `material-icons-extended` library.
- **Accessibility.** Swipe actions also exist as accessibility actions. Rows merge semantics into one description. Status changes use live regions.

### Mail body rendering

A WebView renders sanitized mail HTML. Native text views cannot lay out sender HTML and CSS, so this is the one place where Velin uses a WebView. It is a passive viewer:

1. JavaScript, file access, and content access are off.
2. A Content-Security-Policy blocks all remote loads until the user allows them for that mail.
3. Request interception blocks the same loads a second time.
4. Links never open in place. They go to a callback.

HTML sanitizing belongs to the data layer. Upstream uses DOMPurify in TypeScript. Velin needs an equivalent Kotlin sanitizer with tests against upstream cases.

## Security rules

- Never weaken the end-to-end encryption model to simplify code.
- Keep credentials and database keys in Android Keystore-protected storage. Keep no clear keys in the offline store.
- Exclude all app data from cloud backup and device transfer (`data_extraction_rules.xml`).
- Report decryption and authentication failures as typed results. Never show an empty body instead.
- Send the upstream client version (`cv`) and model version (`v`). Do not replace them with Velin's version.
- No analytics, no tracking, no unneeded permissions.

## Library versions

Versions live in `gradle/libs.versions.toml`. `material3` is pinned to `1.5.0-rc01`, above the Compose BOM, because the Expressive components ship in 1.5. Remove the pin when the BOM includes 1.5 stable.

## Build host notes

The development host is aarch64 Linux. Google ships `aapt2` and the NDK for x86_64 only.

- `aapt2` runs through a qemu-user shim. The path is set in `~/.gradle/gradle.properties`, not in the project.
- Screenshot tests use Robolectric `4.18-beta-1`, the first version with a linux-aarch64 native graphics runtime.
- Rust SDK cross-compilation needs either an x86_64 builder or host clang with NDK sysroots. See the roadmap.
