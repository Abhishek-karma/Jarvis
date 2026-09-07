# Jarvis — Personal AI Assistant for Android

A privacy-first AI assistant that lives on your phone. Connect any OpenAI-compatible, Anthropic, or Google Gemini provider (bring-your-own-key), or run a model entirely on-device. Includes an agent engine with real device tools (calendar, contacts, SMS, alarms, files) behind a permission and confirmation gate, voice mode, and a clean iOS-inspired design language.

**Status:** v0.1.2, active development. Cloud chat, provider management, on-device models, history, agent tools, voice mode, onboarding, and permissions are implemented and unit-tested.

---

## ✨ Features

- **Multi-provider chat** — streaming (SSE) completions via OpenAI-compatible, Anthropic, and Gemini adapters. Providers are fully user-managed: add, edit, verify (live `/models` check), delete, and set defaults in Settings.
- **On-device models** — import or download LiteRT-LM models (`.litertlm` / `.task`) for fully offline chat. The store is checksum-verified, refresh-safe against concurrent imports, and shows real disk usage. No local HTTP servers involved.
- **Smart routing** — per-chat route selector (Auto / On-device / Cloud). Auto classifies each message: quick factual or simple math goes on-device, everything else to the cloud, with forced-fallback notices when the local model isn't ready.
- **Think mode** — Off / Auto / On. When on, `reasoning_effort` is sent to the provider; Auto applies a heuristic (math, logic, multi-step questions).
- **Agent engine** — ReAct-style loop over a typed tool registry: alarms, calendar (read/write), contacts, SMS, calls, files, media volume, system info, web fetch. Tools run in permission tiers (read-only / reversible-write / sensitive); sensitive calls pause for user confirmation, and every call is written to an append-only audit log with redacted arguments.
- **Voice mode** — full-screen voice UI backed by Android or OpenAI STT/TTS with audio record/playback.
- **History** — search, rename, delete, time-grouped conversations persisted in Room; streaming writes are debounced and survive process death.
- **Permissions center** — Settings screen surfacing all seven runtime permissions (mic, notifications, camera, calendar, contacts, SMS, phone) with per-permission state and recovery UX; agent tools request lazily on first use.
- **Privacy-first** — API keys live in `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore), never in the database, logs, or UI. Chat works over HTTPS only. Audit-log parameters are redacted.
- **Design system** — token-driven Compose system in `:core:designsystem`: monochrome surfaces with a single blue accent (`#3D63F6`), SF-style type ramp, dark theme included.

## 🚀 Quick Start

**Prerequisites:** Android Studio Ladybug (2024.2)+, JDK 17, Android SDK Platform 35, and a device/emulator on API 29+.

```bash
git clone <repo-url> jarvis
cd jarvis
./gradlew :app:assembleDebug
```

Install on a connected device:

```bash
./gradlew :app:installDebug
```

Then: complete onboarding, open **Settings → Providers → +**, paste your provider's base URL and API key, tap **Verify & Save**. Keys are entered at runtime through the in-app UI — never in `local.properties` or build config.

## 🧪 Running Tests

All JVM unit tests:

```bash
./gradlew :feature:chat:testDebugUnitTest :feature:settings:testDebugUnitTest :core:network:testDebugUnitTest
```

Run one test class (from the module directory):

```bash
./gradlew :core:network:testDebugUnitTest --tests "*OpenAiCompatibleProviderTest*"
```

> **Gotcha:** JUnit 5 tests are only discovered when the module's `android { testOptions { unitTests.all { it.useJUnitPlatform() } } }` block is present. New modules with tests need both the JUnit 5 deps and this block.

Provider adapters are contract-tested against MockWebServer with recorded fixtures in `core/network/src/test/resources/fixtures/` — no real endpoints, no API credits burned. Lint runs in CI (`./gradlew lint`).

## 🏗️ Architecture

Multi-module and layered: **Presentation → ViewModel → Domain → `:core:*`**. Features never depend on each other.

```
:app                         ← application module, Hilt DI graph root, NavHost
:core
  :common                    ← domain models (Message, Conversation, ProviderConfig), dispatchers
  :designsystem              ← theme, tokens, typography, shared Compose components
  :network                   ← LlmProvider + OpenAI-compatible / Anthropic / Gemini adapters,
                               ProviderManager (adapter cache), NetworkModule
  :database                  ← Room (entities, DAOs, migrations), repositories,
                               ApiKeyStore (EncryptedSharedPreferences, AES-256-GCM)
  :agent                     ← AgentEngine (ReAct), ToolRegistry, permission tiers, audit logger
  :voice                     ← SttProvider / TtsProvider interfaces + Android & OpenAI impls
  :ml                        ← on-device LLM (LiteRT-LM), model catalog, checksum-verified store
  :navigation                ← Routes only
  :preferences               ← DataStore-backed user preferences
:feature
  :chat                      ← ChatViewModel (MVI), ChatScreen, HistoryDrawerScreen,
                               VoiceModeScreen, routing classifier, markdown renderer
  :settings                  ← SettingsViewModel, SettingsScreen, ProvidersListScreen,
                               ProviderEditScreen, Onboarding, Permissions, About
```

**Key conventions**

- **DI:** Hilt everywhere. `@HiltViewModel` + `@Inject constructor` for ViewModels; `:core:database` binds the `ConversationRepository` interface to `ChatRepository` via `@Binds`.
- **State:** MVI — each ViewModel exposes an immutable `UiState` `StateFlow` and a sealed event API; UI collects via `collectAsStateWithLifecycle()`.
- **Version catalog:** every dependency pin lives in `gradle/libs.versions.toml`, accessed as `libs.<name>`.
- **API keys** never go in Room — only in `ApiKeyStore`. Provider configs (no key) live in the `providers` Room table.
- **Design tokens only** — spacing, radius, type, and color come from `:core:designsystem` tokens; no raw dp/sp/color literals in feature code.
- **Adding a provider** = implement `LlmProvider` + a settings entry; no changes to `:feature:chat`.

## 🧩 Tech Stack

| Area | Choice |
|------|--------|
| Language | Kotlin 2.2, Jetpack Compose (BOM 2024.12.01), Material 3 |
| DI | Hilt 2.58 + KSP |
| Persistence | Room 2.8 (schema v3 + migrations), DataStore, EncryptedSharedPreferences |
| Networking | OkHttp 4.12 (SSE streaming), Moshi, kotlinx-serialization |
| On-device ML | LiteRT LM 0.15 |
| Async | kotlinx-coroutines 1.11, Flow |
| Tests | JUnit 5, MockK, Turbine, MockWebServer |
| SDK | minSdk 29, target/compile 35, Java 17 |

## 📐 Build Config

- **applicationId:** `com.aistudio.jarvis.abpk`
- **versionName:** `0.1.2` (versionCode 3)
- **Variants:** `debug`, `release` (R8 + resource shrinking, `isMinifyEnabled = true`)
- **CI:** lint → unit tests → assembleDebug (`.github/workflows/ci.yml`), plus release workflow (`.github/workflows/release.yml`) and nightly workflow

## 📄 License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
