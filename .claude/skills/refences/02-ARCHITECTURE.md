# System Architecture

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────┐
│  Presentation (Jetpack Compose)                  │
│  HomeScreen, VoiceScreen, Drawers, AgentSheet     │
└──────────────────┬────────────────────────────────┘
                    │
┌──────────────────▼────────────────────────────────┐
│  ViewModel (StateFlow + MVI)                       │
│  ChatViewModel, VoiceViewModel, AgentViewModel      │
└──────────────────┬────────────────────────────────┘
                    │
┌──────────────────▼────────────────────────────────┐
│  Domain (Use Cases + Orchestrator)                  │
│  ChatOrchestrator, AgentLoop, MemoryManager         │
└──────────────────┬────────────────────────────────┘
                    │
┌──────────┬────────┼────────┬──────────┬───────────┐
│   LLM    │ Agent  │ Voice  │  Photo   │   Tools    │
│  Layer   │ Tools  │ Pipe   │  Engine  │  Registry  │
└────┬─────┴───┬────┴───┬────┴────┬─────┴─────┬──────┘
     │         │        │         │           │
┌────▼────┐ ┌──▼───┐ ┌──▼───┐ ┌───▼────┐ ┌────▼────┐
│  Cloud  │ │Local │ │Native│ │   ML   │ │ Android │
│  APIs   │ │Models│ │Bridge│ │Runtime │ │  APIs   │
└─────────┘ └──────┘ └──────┘ └────────┘ └─────────┘
```

Each layer only calls the layer directly below it. The Domain layer never touches Android framework APIs directly — it goes through repository interfaces implemented in `:core:*` modules, which keeps Domain unit-testable on the JVM without Robolectric.

## 2. Module Structure

```
:app                         <- application module, DI graph root, nav host
:core
  :common                    <- utilities, extensions, Result/Either types, coroutine dispatchers
  :designsystem               <- theme, colors, typography, shared Compose components
  :network                    <- HTTP clients, SSE, WebSocket, provider adapters
  :database                   <- Room, DataStore, ObjectBox wrappers + DAOs
  :ml                          <- ONNX Runtime, MediaPipe, TFLite wrappers, model loader
  :voice                       <- STT, TTS, VAD, wake word
  :agent                       <- ReAct loop, tool registry, planner, permission gate
:feature
  :chat                        <- home screen, message bubbles, composer
  :voice                       <- voice mode screen, orb
  :history                     <- chat list, search
  :settings                    <- drawers, provider config
  :photo-tools                 <- gallery features, editor screens
:native
  :llama                       <- llama.cpp JNI bindings (local LLM inference)
  :whisper                     <- whisper.cpp JNI bindings (local STT)
  :piper                       <- piper TTS JNI bindings (local TTS)
```

**Dependency rule:** `:feature:*` depends on `:core:*`, never on another `:feature:*` module. Cross-feature navigation goes through a shared `:core:navigation` contract (route definitions only, no ViewModel sharing). `:native:*` modules are only depended on by their matching `:core` wrapper (`:core:ml` for llama/whisper, `:core:voice` for piper) — nothing above `:core` touches JNI directly.

## 3. State Management

- Single source of truth per screen via `StateFlow`.
- Shared state lives in repositories (singletons via Hilt), not in ViewModels, so multiple screens observing the same data (e.g., active provider) stay in sync.
- Unidirectional data flow: UI events flow up via a sealed `UiEvent` class per screen; state flows down via a single `UiState` data class per screen.
- `SavedStateHandle` persists screen-critical state (active chat ID, composer draft text) across process death.
- Compose reads state via `collectAsStateWithLifecycle()` exclusively — never raw `collectAsState()` — so collection pauses when the screen is backgrounded.
- Each ViewModel's `UiState` is immutable; updates go through `MutableStateFlow.update { }` to avoid lost-update races under concurrent emission.

## 4. Concurrency Model

| Layer | Dispatcher | Notes |
|-------|-----------|-------|
| UI | Main (Compose) | never blocked; all suspend calls launched from `viewModelScope` |
| LLM network calls | `Dispatchers.IO` | streaming responses use a cold `Flow` collected on IO, mapped to UI on Main |
| Local LLM inference | `Dispatchers.Default`, pinned to a dedicated 4-thread pool | isolated from other `Default`-dispatcher work to avoid starving Compose recomposition scheduling |
| ML inference (photo, embeddings) | `Dispatchers.Default` | shares the general pool; photo ops show progress UI since they can exceed 1s |
| Voice pipeline | single-thread executor (own thread, not a coroutine dispatcher) | audio callbacks are latency-sensitive and must not be preempted by coroutine scheduling |
| Database | `Dispatchers.IO` via Room's suspend DAOs | writes batched where possible (e.g., streaming token appends debounced at 100ms) |
| Background work | `WorkManager` | model downloads, memory extraction, index rebuilds |

**Cancellation contract:** every long-running operation (LLM stream, agent loop, photo op) is cancellable via structured concurrency — cancelling the owning `viewModelScope` job must actually stop the underlying network/native call, not just detach the UI. Provider adapters and the `:native:llama` JNI bridge both implement `close()`/abort hooks invoked from `invokeOnCompletion` on cancellation.

## 5. Persistence

| Data | Storage | Notes |
|------|---------|-------|
| Conversations & messages | Room (SQLite) | see `09-DATA-MODELS.md` for schema |
| Embeddings (memory, semantic search) | ObjectBox (vector index) | separate DB file from Room; referenced by foreign UUID, not FK |
| Preferences | Proto DataStore | typed, versioned schema; migrations via `DataMigration` |
| API keys | EncryptedSharedPreferences (AES-256, Android Keystore-backed) | never written to Room or logs |
| Photos cache | app-private dir + MediaStore | originals untouched; edits write new MediaStore entries unless user chooses "overwrite" |
| Local LLM / STT / TTS model weights | app-private dir, encrypted at rest | downloaded on demand, checksum-verified before load |
| Audit log (agent tool calls) | Room, append-only table | see `14-SECURITY.md §Audit Log` |

## 6. Networking

- **HTTP:** Retrofit + OkHttp.
- **Streaming:** OkHttp `EventSource` (SSE) for providers using SSE; Ktor `WebSocket` client for providers using realtime WebSocket APIs (voice-to-voice).
- **Realtime:** WebSocket used specifically for provider realtime/voice APIs where offered; falls back to STT→text-LLM→TTS pipeline otherwise.
- **Cert pinning:** enabled for the fixed set of known provider domains shipped in-app; custom/self-hosted endpoints (e.g., local network LLM servers) are exempted from pinning but still require HTTPS or an explicit user override for HTTP-on-LAN.
- **Retry:** exponential backoff (base 500ms, ×2, jittered), max 3 attempts, only for idempotent/retryable errors (timeouts, 429, 5xx) — never retried automatically for 4xx auth errors.
- **Timeouts:** connect 10s, read 60s (120s if the request explicitly opts into "reasoning" mode), write 10s.
- **Offline detection:** `ConnectivityManager.NetworkCallback`-backed reactive flag consumed by the smart-routing decision tree and by the composer (queues messages instead of failing).

## 7. Security

Full detail in `14-SECURITY.md`; architectural summary:

- API keys: AES-256 via Android Keystore, hardware-backed where available.
- Model files: encrypted at rest, checksum-verified on load.
- HTTPS only for all network calls except explicitly user-approved local-network endpoints.
- Cert pinning for known providers.
- Agent actions: tiered confirmation for sensitive operations (see `06-AGENT.md §Permissions`).
- Audit log: every tool call recorded with timestamp, tool name, redacted parameters, and result status.
- Root detection: warns the user, does not block functionality (some power users run rooted devices intentionally).
- ProGuard/R8: enabled in release builds, with a maintained keep-rules file for reflection-based libraries (Room, Retrofit, Moshi).
