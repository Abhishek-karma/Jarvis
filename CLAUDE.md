# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Jarvis — a multi-provider Android AI assistant. Kotlin + Jetpack Compose, Hilt DI, Room, OkHttp SSE streaming. Currently at v0.1 MVP: chat UI, OpenAI-compatible provider management, local history. Requirements live in `.claude/skills/refences/` (numbered `01-PRD.md` … `15-ROADMAP.md`).

**Design system:** vendored packs in `design/` (ChatGPT + Claude from awesome-ios-design-md). Monochrome canvas (`#FFFFFF`/`#212121`), Claude Orange `#D97757` single accent, serif assistant prose, bubble-less assistant messages, darker history sidebar, full-screen blue voice sphere. Tokens/components in `:core:designsystem` (`JarvisColors`, `JarvisText`, `JarvisBubbleShapes`, `JarvisSendButton`, `StreamingCursor`, `JarvisMark`, `Motion`). See `design/README.md` — match it exactly for new UI.

## Commands

Build (Windows, Git Bash):
```bash
./gradlew :app:assembleDebug
```

Run all JVM unit tests:
```bash
./gradlew :feature:chat:testDebugUnitTest :feature:settings:testDebugUnitTest :core:network:testDebugUnitTest
```

Run a single test class (from the module dir):
```bash
./gradlew :core:network:testDebugUnitTest --tests "*OpenAiCompatibleProviderTest*"
```

**Gotcha:** JUnit5 tests are only discovered if the module's `android { testOptions { unitTests.all { it.useJUnitPlatform() } } }` block is present. Adding a new module with tests requires both the JUnit5 deps and this block.

## Architecture

Multi-module, layered (02-ARCHITECTURE.md §2). Presentation → ViewModel → Domain → `:core:*`.

- `:core:common` — domain models (`Message`, `Conversation`, `ProviderConfig`), `DispatcherProvider`, `TimeGrouping`.
- `:core:database` — Room (entities, DAOs, `JarvisDatabase`), repositories (`ConversationRepository` interface + `ChatRepository` impl, `ProviderRepository`), `ApiKeyStore` (EncryptedSharedPreferences, AES-256-GCM).
- `:core:network` — `LlmProvider` interface + `OpenAiCompatibleProvider` (OkHttp SSE), `ProviderManager` (caches per-provider adapters, Hilt singleton), `NetworkModule`.
- `:core:designsystem` — `JarvisTheme`, `Spacing`, `Radius`, `JarvisTypography`, `JarvisShapes`.
- `:core:navigation` — `Routes` object only; features never depend on each other.
- `:feature:chat` — `ChatViewModel` (MVI), `ChatScreen`, `HistoryViewModel`, `HistoryDrawerScreen` (ModalNavigationDrawer).
- `:feature:settings` — `SettingsViewModel`, `SettingsScreen`, `ProvidersListScreen`, `ProviderEditScreen`.
- `:app` — `JarvisApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`, NavHost wiring all features).

## Key conventions

- **DI:** Hilt. `@HiltViewModel` + `@Inject constructor` for ViewModels. `:core:database` binds the `ConversationRepository` interface to `ChatRepository` via `RepositoryModule` (`@Binds`). `ProviderRepository` is concrete (`@Inject`) and auto-provisioned.
- **Version catalog:** all dependency pins in `gradle/libs.versions.toml`; access via `libs.<name>` (dots → accessor groups, e.g. `libs.okhttp.mockwebserver`, `libs.junit5.api`).
- **MVI pattern:** each ViewModel exposes an immutable `UiState` StateFlow + a sealed event/action API; UI reads via `collectAsStateWithLifecycle()`.
- **API keys** never go in Room — only in `ApiKeyStore`. Providers configs (no key) live in the `providers` Room table.
- **Adding a provider** = implement `LlmProvider` + a settings entry; no changes to `:feature:chat`.

## Provider contract tests

`OpenAiCompatibleProviderTest` runs against MockWebServer with fixtures in `core/network/src/test/resources/fixtures/` (recorded once, replayed — no real endpoints).
