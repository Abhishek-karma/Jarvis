---
name: jarvis-android
description: Build and develop the Jarvis Android personal assistant app (multi-provider LLM chat, agent mode, voice, on-device photo tools). Use when working in D:\jarvis on any Jarvis code — implementing features, fixing bugs, adding modules, or running the app.
---

# Jarvis Android Development

## Project Snapshot

Jarvis is a local-first personal AI assistant for Android (Kotlin + Jetpack Compose, min API 29, target API 34+). The authoritative requirement pack lives in `.claude/skills/refences/` (15 docs, `README.md` is the index). The repo already implements the v0.1 MVP baseline (chat UI, provider management, local history). Development proceeds milestone by milestone per `.claude/skills/refences/15-ROADMAP.md` (currently v0.1 MVP).

## Step 1: Scope the Work Against the Docs

Before writing any code, find the governing doc:

| Working on | Read first |
|---|---|
| Overall vision, metrics, release plan | `.claude/skills/refences/01-PRD.md` |
| Modules, state, concurrency, persistence | `.claude/skills/refences/02-ARCHITECTURE.md` (load-bearing — every other doc assumes it) |
| Feature acceptance criteria & error states | `.claude/skills/refences/03-FEATURES.md` |
| UI tokens, screens, components | `.claude/skills/refences/04-DESIGN.md` |
| LLM providers, streaming, failover | `.claude/skills/refences/05-LLM-PROVIDERS.md` |
| Agent system, tools, permissions | `.claude/skills/refences/06-AGENT.md` |
| Photo editing pipeline | `.claude/skills/refences/07-PHOTO-TOOLS.md` |
| Voice, wake word, STT/TTS | `.claude/skills/refences/08-VOICE.md` |
| Room/ObjectBox schemas, migrations | `.claude/skills/refences/09-DATA-MODELS.md` |
| Internal module interfaces | `.claude/skills/refences/10-API-REFERENCE.md` |
| Dev environment, first build | `.claude/skills/refences/11-SETUP.md` |
| Build variants, CI, signing | `.claude/skills/refences/12-BUILD-DEPLOY.md` |
| Test strategy, coverage floors | `.claude/skills/refences/13-TESTING.md` |
| Threat model, key storage, permissions | `.claude/skills/refences/14-SECURITY.md` |
| Milestone sequencing | `.claude/skills/refences/15-ROADMAP.md` |

Check `.claude/skills/refences/15-ROADMAP.md` to confirm the feature is in scope for the current milestone — explicitly do NOT build ahead (e.g. no agent mode, photo tools, local LLM, or wake word in v0.1). When a request conflicts with a doc, surface the conflict and follow the doc.

## Step 2: Architecture Rules (from `.claude/skills/refences/02-ARCHITECTURE.md`)

```
:app                    <- DI graph root, nav host
:core
  :common  :designsystem  :network  :database  :ml  :voice  :agent
:feature
  :chat  :voice  :history  :settings  :photo-tools
:native
  :llama  :whisper  :piper   <- JNI: llama.cpp, whisper.cpp, piper
```

- **Layering:** Presentation → ViewModel → Domain → core layer modules → native APIs. Domain never touches Android framework APIs — it uses repository interfaces in `:core:*` so it stays JVM-unit-testable.
- **Dependency rule:** `:feature:*` depends on `:core:*`, never on another `:feature:*`. Cross-feature navigation goes through `:core:navigation` (routes only). Only the matching `:core` wrapper may touch a `:native:*` module.
- **State:** one `StateFlow<UiState>` per screen (immutable, updated via `MutableStateFlow.update { }`); events up via sealed `UiEvent`; read with `collectAsStateWithLifecycle()` only. Shared state lives in Hilt singleton repositories, not ViewModels.
- **Concurrency dispatchers:** network/DB → IO; local LLM → `Dispatchers.Default` pinned to a dedicated 4-thread pool; ML inference → Default (show progress UI for ops > 1s); voice pipeline → single-thread executor, not a coroutine dispatcher; background work → WorkManager.
- **Cancellation contract:** every long-running op (LLM stream, agent loop, photo op) must genuinely cancel the underlying network/native call via `close()`/abort hooks invoked from `invokeOnCompletion` — detaching the UI is not enough.
- **Errors:** `Result<T>` at suspend boundaries; sealed `Error` subtypes inside Flows (`ChatStreamEvent.Error`, `PhotoOpProgress.Failed`). Never throw across a module boundary.
- **Persistence:** Room for conversations/audit log, ObjectBox for embeddings, Proto DataStore for prefs, EncryptedSharedPreferences for API keys. Never write keys to Room or logs.

## Step 3: Non-Negotiable Constraints

- **Privacy:** no analytics on message/photo/memory content, ever. Telemetry is opt-in, aggregate-only. Memory extraction excludes health/financial/government-ID categories and anything from private-marked chats.
- **API keys:** user-supplied, `EncryptedSharedPreferences` (AES-256, Keystore-backed), never in repo, `local.properties`, logs, or Crashlytics breadcrumbs.
- **Offline-first:** 90% of features work offline; composer queues rather than fails on network loss.
- **Size/battery:** < 800MB bundled ML models (local LLM weights excluded); < 5%/hour battery for active voice.
- **Permissions:** never requested at launch — always contextual, with rationale, and denial always routes to a fallback (never a dead end).
- **Agent safety:** tool tiers are fixed at registration (READ_ONLY / REVERSIBLE_WRITE / SENSITIVE); Sensitive tier always requires explicit user confirmation; every tool call writes an append-only audit log row.

## Step 4: Test & Build

- Test floors: 80% line coverage on `:core:*` and `:feature:*` ViewModels (JUnit5 + MockK + Turbine, JVM-only); 100% of `LlmProvider` surface per adapter via MockWebServer recorded fixtures. CI fails below the floor.
- Provider/feature tests never hit real network — use MockWebServer fixtures (also backs the debug "dev provider").
- ktlint formatting is CI-enforced — run `./gradlew ktlintFormat` before pushing.
- Build: `./gradlew :app:assembleDebug`; install: `./gradlew :app:installDebug`. NDK 26.x + CMake 3.22.1+ required for `:native:*`. Local voice/LLM testing needs a physical arm64 device (emulator x86_64 doesn't exercise the native path).
- Every new feature PR includes: unit tests at the floor, contract tests for any new provider/tool surface, and coverage must not drop.

## Step 5: Coded Conventions

- Kotlin idiomatic, no reflection-dependent patterns in `:core:*` public APIs.
- Version catalog `gradle/libs.versions.toml` is the single source for dependency pins.
- Streaming token appends to DB are debounced (100ms); DB writes batched where possible.
- Model files: checksummed manifest, encrypted at rest, lazy-loaded, one local LLM resident at a time.

## Related Skills

- `jarvis-provider` — add an LLM provider adapter (`:core:network`)
- `jarvis-agent-tool` — add an agent tool (`:core:agent`)
- `jarvis-photo-tool` — add a photo tool (`:core:ml`, `:feature:photo-tools`)
