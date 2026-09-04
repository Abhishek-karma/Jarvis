# LLM Provider Integration

## 1. Supported Providers (v1.0 target: 8+)

| Provider | Protocol | Streaming | Vision | Realtime voice |
|---|---|---|---|---|
| OpenAI | REST, OpenAI schema | SSE | Yes | WebSocket (Realtime API) |
| Anthropic | REST, Anthropic schema | SSE | Yes | No (STT→text→TTS pipeline) |
| Google Gemini | REST, Gemini schema | SSE | Yes | WebSocket (Live API) |
| Mistral | REST, OpenAI-compatible | SSE | Model-dependent | No |
| Groq | REST, OpenAI-compatible | SSE | No | No |
| xAI (Grok) | REST, OpenAI-compatible | SSE | Model-dependent | No |
| Local (llama.cpp) | in-process JNI | token callback | Model-dependent (LLaVA-class) | No |
| Custom / self-hosted | user-declared schema (OpenAI-compatible assumed) | SSE | Configurable | No |

## 2. Provider Adapter Interface

Every provider — cloud or local — implements a single Kotlin interface so the rest of the app never branches on provider identity:

```kotlin
interface LlmProvider {
    val id: String
    val capabilities: ProviderCapabilities // vision, maxContext, supportsTools, supportsReasoning, supportsRealtimeVoice

    suspend fun listModels(): Result<List<ModelInfo>>

    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent>
    // ChatStreamEvent: TokenDelta, ReasoningDelta, ToolCallRequested, Error, Done

    suspend fun estimateCost(request: ChatRequest): CostEstimate?

    fun close() // aborts in-flight streams, releases native/socket resources
}
```

- `ChatOrchestrator` (domain layer) depends only on `LlmProvider`, never on a concrete provider class.
- Adding a provider = implementing the interface + a settings-screen entry; no changes to `:feature:chat`.

## 3. Authentication

- API keys entered by the user, stored in `EncryptedSharedPreferences` (see `02-ARCHITECTURE.md §7`), never transmitted anywhere except the provider's own endpoint over TLS.
- OAuth-based providers (if added later) use `AppAuth` with PKCE; refresh tokens stored the same way as API keys.
- Key validation happens via a lightweight `listModels()` call on save — a failed call surfaces the specific HTTP status, not a generic "invalid key."

## 4. Streaming Contract

All adapters normalize to the same `ChatStreamEvent` sealed class regardless of wire format (SSE `data:` lines, WebSocket frames, or JNI callbacks from the local model):

```
TokenDelta(text: String)
ReasoningDelta(text: String)          // only if capabilities.supportsReasoning
ToolCallRequested(name, argsJson)     // only if capabilities.supportsTools
Usage(promptTokens, completionTokens)
Error(code, message, retryable: Boolean)
Done
```

`ChatOrchestrator` collects this Flow and maps it to UI state; it never sees provider-specific payload shapes.

## 5. Failover & Fallback

- If the chat's configured model becomes unavailable mid-session (deprecated, provider outage), the orchestrator falls back to the chat's or the global default model and surfaces the non-blocking banner defined in `03-FEATURES.md` Feature 1.
- If a cloud provider request fails after the retry policy in `02-ARCHITECTURE.md §6` is exhausted, and a local model is downloaded, the orchestrator offers a one-tap "try locally instead" action rather than a dead-end error (only for queries the smart-routing tree would have allowed locally in the first place).

## 6. Rate Limits & Cost

- Each adapter surfaces provider-declared rate-limit headers (where present) to a shared `RateLimitTracker`, which the composer consults to preemptively disable send with a countdown rather than firing a request guaranteed to 429.
- Cost estimates use a locally bundled, periodically-updated pricing table per provider; if a model is missing from the table, the estimator shows "cost unknown" rather than a guess.

## 7. Local Model Management

- Local models (GGUF format for llama.cpp) are downloaded from a curated, checksummed manifest — never arbitrary URLs.
- Only one local model resident in memory at a time; switching models unloads the previous one first (native memory is not GC'd automatically).
- Local model capability metadata (context length, vision support, expected tokens/sec on reference hardware) ships in the same manifest so smart-routing (`03-FEATURES.md` Feature 6) can reason about it without a runtime probe.
