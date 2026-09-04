# Jarvis — Personal AI Assistant for Android

A local-first AI assistant: multi-provider LLM chat, streaming responses, push-to-talk voice,
and a clean JARVIS-style chat UI. Requirements live in `.claude/skills/refences/` (15-doc pack).

**Current milestone: v0.1 (MVP)** — see `.claude/skills/refences/15-ROADMAP.md`.

## Modules

```
:app                 DI graph root, nav host
:core:common         domain models, dispatchers
:core:designsystem   design tokens, theme (04-DESIGN)
:core:database       Room schema, repositories, EncryptedSharedPreferences key store
:core:network        LlmProvider interface, OpenAI-compatible SSE adapter, ProviderManager
:core:voice          push-to-talk recorder, STT/TTS providers, audio playback
:core:navigation     cross-feature route contract
:core:agent          (v0.5 groundwork) tool registry, ReAct engine, audit log
:feature:chat        chat screen, composer, markdown, history drawer
:feature:settings    provider list/editor, key validation
```

Dependency rule: `:feature:*` depends only on `:core:*`, never on other features.
Cross-feature navigation goes through `:core:navigation` (02-ARCHITECTURE.md §2).

## Build & Run

```bash
./gradlew :app:assembleDebug     # build
./gradlew test -x :app:test      # all unit tests
./gradlew :app:installDebug      # install to connected device/emulator
```

Requires JDK 17, Android SDK 35. NDK is not needed until `:native:*` modules land (v0.5).

## v0.1 Scope (shipped here)

- Multi-provider chat via the OpenAI-compatible adapter (OpenAI, Mistral, Groq, xAI,
  LM Studio, Ollama `/v1`, any self-hosted OpenAI-dialect server)
- Streaming responses with token accumulation, 100ms-debounced persistence, cancel mid-stream
  (partial kept, marked "stopped")
- Markdown rendering with code blocks
- History drawer: time-grouped conversations, pin/rename/delete
- Settings: provider CRUD, API keys stored in EncryptedSharedPreferences (AES-256-GCM,
  Keystore-backed), key validation via `listModels()` with specific HTTP status surfaced
- Push-to-talk voice: record → cloud STT → send; TTS playback of responses
- Retry policy: exponential backoff (500ms ×2, jittered, max 3), only for timeout/429/5xx

**Explicitly out of v0.1** (per roadmap): agent mode, photo tools, local LLM, wake word,
semantic search, memory system.

## Security Notes

- API keys never leave `EncryptedSharedPreferences` except to their own provider endpoint
  over TLS (14-SECURITY.md §2)
- No analytics on message/photo content, ever
- No permission is requested before the screen that needs it (mic is requested on first
  push-to-talk tap)

## Testing

- `:core:network` adapter contract tests run against MockWebServer with recorded fixtures —
  never a real provider (13-TESTING.md §2)
- ViewModel tests are JVM-only with MockK + Turbine
- CI (`.github/workflows/ci.yml`): unit tests → assembleDebug on every PR
