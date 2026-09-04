---
name: jarvis-provider
description: Add or modify an LLM provider adapter for Jarvis (OpenAI, Anthropic, Gemini, Mistral, Groq, xAI, local llama.cpp, custom/self-hosted). Use when implementing a class that implements LlmProvider, touching the streaming contract, auth, failover, rate limits, or cost estimation in :core:network.
---

# Jarvis LLM Provider Adapter

## Step 1: Confirm the Contract

Every provider — cloud or local — implements the single `LlmProvider` interface so the rest of the app never branches on provider identity (`.claude/skills/refences/05-LLM-PROVIDERS.md` §2, `.claude/skills/refences/10-API-REFERENCE.md` §1):

```kotlin
interface LlmProvider {
    val id: String
    val capabilities: ProviderCapabilities // vision, maxContext, supportsTools, supportsReasoning, supportsRealtimeVoice

    suspend fun listModels(): Result<List<ModelInfo>>

    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent>

    suspend fun estimateCost(request: ChatRequest): CostEstimate?

    fun close() // aborts in-flight streams, releases native/socket resources
}
```

Rules that follow from this:
- `ChatOrchestrator` depends only on `LlmProvider`, never on a concrete provider class.
- Adding a provider = implementing the interface + a settings-screen entry. **No changes to `:feature:chat`.**
- `ChatRequest` carries `conversationHistory`, `systemPrompt`, `memoryContext`, `toolsAvailable` (null when not agent mode), `thinkMode` (OFF/ON/AUTO), `attachments`.

## Step 2: Normalize Streaming to ChatStreamEvent

All adapters — regardless of wire format (SSE `data:` lines, WebSocket frames, or JNI callbacks from the local model) — normalize to the same sealed class (`.claude/skills/refences/05-LLM-PROVIDERS.md` §4):

```
TokenDelta(text)
ReasoningDelta(text)                // only if capabilities.supportsReasoning
ToolCallRequested(name, argsJson)   // only if capabilities.supportsTools
Usage(promptTokens, completionTokens)
Error(code, message, retryable)     // retryable=true only for timeouts, 429, 5xx
Done
```

- Emit `ReasoningDelta` **before** `TokenDelta` when the provider streams reasoning separately; the UI collapses the reasoning block when the answer starts.
- Never emit provider-specific payload shapes up the Flow — the orchestrator must not see them.
- If the provider lacks a capability, don't fake it: no reasoning support → never emit `ReasoningDelta` and let Think Mode fall back to Off with a one-time notice (`.claude/skills/refences/03-FEATURES.md` Feature 2).

## Step 3: Networking & Resilience (.claude/skills/refences/02-ARCHITECTURE.md §6)

- HTTP: Retrofit + OkHttp. SSE: OkHttp `EventSource`. Realtime voice: Ktor WebSocket client (only where the provider offers it).
- Timeouts: connect 10s, read 60s (120s for reasoning-opted requests), write 10s.
- Retry: exponential backoff base 500ms ×2, jittered, max 3 attempts, **only** for idempotent errors (timeouts, 429, 5xx). Never auto-retry 4xx auth errors.
- Cert pinning for the fixed set of known provider domains. Custom/self-hosted endpoints are pinning-exempt but require HTTPS, or an explicit one-time user acknowledgment for HTTP-on-LAN (private IP ranges only by default — public HTTP endpoints need an additional override).
- Cancellation: cancelling the collecting scope must abort the underlying socket/JNI call — implement it in the adapter's `close()` / `invokeOnCompletion` hook, don't just stop collecting.
- Surface provider rate-limit headers to the shared `RateLimitTracker` so the composer can preemptively disable send with a countdown.

## Step 4: Auth & Keys (.claude/skills/refences/14-SECURITY.md §2)

- API keys come from the user via the settings UI, stored in `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore-backed).
- Keys are never: logged, written to Room, included in Crashlytics breadcrumbs, or transmitted anywhere except the provider's own endpoint over TLS.
- Validate keys on save via a lightweight `listModels()` call — surface the specific HTTP status ("401: Provider rejected this key"), never a generic "invalid key."

## Step 5: Cost Estimation

- Use the locally bundled pricing table; if the model is missing, show "cost unknown" — never guess (`.claude/skills/refences/05-LLM-PROVIDERS.md` §6).
- Pricing is user-editable if stale.

## Step 6: Failover & Fallback

- Configured model unavailable mid-session → fall back to the chat's or global default model + non-blocking banner.
- Cloud request fails after retries exhausted AND a local model is downloaded → offer one-tap "try locally instead" (only for queries smart-routing would have allowed locally).
- Never create a dead-end error — every error state has a recovery action (`.claude/skills/refences/03-FEATURES.md` Feature 1 error table).

## Step 7: Local (llama.cpp) Provider Specifics

- GGUF models only, downloaded from the curated checksummed manifest — never arbitrary URLs (`.claude/skills/refences/05-LLM-PROVIDERS.md` §7).
- Only one local model resident in memory; switching unloads the previous first (native memory isn't GC'd).
- Runs through `:native:llama` JNI, wrapped by `:core:ml` — nothing above `:core` touches JNI directly.
- Capability metadata (context length, vision, expected tokens/sec) ships in the manifest so smart-routing can reason without a runtime probe.
- Token callbacks from JNI → map to the same `ChatStreamEvent` sealed class.

## Step 8: Contract Tests (mandatory, .claude/skills/refences/13-TESTING.md)

- 100% of the `LlmProvider` interface surface per adapter, against MockWebServer with recorded fixture responses — no test ever hits a real provider endpoint.
- Record streaming as ordered event sequences replayed with realistic timing, **including deliberately-injected mid-stream errors**, to exercise retry/cancellation logic.
- Cover: normal stream, mid-stream network loss (partial response kept, marked "connection lost"), 401/429/5xx paths, tool-call event emission, reasoning stream, and `close()` aborting an in-flight stream.
- The same fixtures back the debug-build "dev provider" so manual testing and automated tests exercise the same contract.
