# API Reference — Internal Module Contracts

This is the contract surface between modules (per `02-ARCHITECTURE.md §2`), not a public/external API — Jarvis does not expose a public API in v1.0.

## 1. `:core:network` — `LlmProvider`

See full interface in `05-LLM-PROVIDERS.md §2`. Key types:

```kotlin
data class ChatRequest(
    val conversationHistory: List<Message>,
    val systemPrompt: String?,
    val memoryContext: List<String>,       // injected memory facts, per Feature 7
    val toolsAvailable: List<ToolDefinition>?, // null if not agent mode
    val thinkMode: ThinkMode,              // OFF | ON | AUTO
    val attachments: List<Attachment>
)

sealed class ChatStreamEvent {
    data class TokenDelta(val text: String) : ChatStreamEvent()
    data class ReasoningDelta(val text: String) : ChatStreamEvent()
    data class ToolCallRequested(val name: String, val argsJson: String) : ChatStreamEvent()
    data class Usage(val promptTokens: Int, val completionTokens: Int) : ChatStreamEvent()
    data class Error(val code: String, val message: String, val retryable: Boolean) : ChatStreamEvent()
    object Done : ChatStreamEvent()
}
```

## 2. `:core:agent` — `Tool`

```kotlin
interface Tool {
    val name: String
    val description: String                // sent to the LLM as the function description
    val parametersSchema: JsonSchema
    val tier: PermissionTier                // READ_ONLY | REVERSIBLE_WRITE | SENSITIVE

    suspend fun execute(argsJson: String): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val observationText: String,            // fed back into the ReAct loop as the Observation
    val structuredData: Map<String, Any>? = null,
    val error: String? = null
)
```

`ToolRegistry.register(tool: Tool)` is the single entry point for built-in tools, MCP-derived tools, and OpenAPI-imported tools alike (`06-AGENT.md §2`).

## 3. `:core:voice` — `VoicePipeline`

```kotlin
interface VoicePipeline {
    val state: StateFlow<VoiceState>        // IDLE | LISTENING | THINKING | SPEAKING | ERROR
    fun startSession()
    fun endSession()
    fun mute()
    val transcript: Flow<TranscriptEvent>   // PartialResult, FinalResult, BargeInDetected
}
```

## 4. `:core:ml` — `PhotoToolEngine`

```kotlin
interface PhotoToolEngine {
    suspend fun preview(tool: PhotoToolId, input: Bitmap): Bitmap        // downsampled, fast
    suspend fun apply(tool: PhotoToolId, input: Uri, params: Map<String, Any>): Flow<PhotoOpProgress>
}

sealed class PhotoOpProgress {
    data class InProgress(val fraction: Float) : PhotoOpProgress()
    data class Complete(val resultUri: Uri) : PhotoOpProgress()
    data class Failed(val error: String, val fallbackAvailable: Boolean) : PhotoOpProgress()
}
```

## 5. `:core:database` — Repository Contracts

```kotlin
interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    suspend fun getConversation(id: String): Conversation?
    suspend fun upsert(conversation: Conversation)
    suspend fun delete(id: String)          // cascades messages, triggers embedding cleanup per 09-DATA-MODELS.md §5
}

interface MemoryRepository {
    suspend fun query(embedding: FloatArray, limit: Int): List<MemoryFact>
    suspend fun extractAndStore(conversationId: String)   // background job entry point
    suspend fun delete(uuid: String)
    fun observeAll(): Flow<List<MemoryFact>>
}
```

## 6. Error Model (shared across modules)

All suspend functions that can fail return `Result<T>` (Kotlin stdlib) rather than throwing, except where a `Flow` is more natural — those use the `Error` sealed subtype pattern shown above (`ChatStreamEvent.Error`, `PhotoOpProgress.Failed`) instead of throwing inside the flow. This keeps error handling explicit and consistent at every module boundary, and is enforced via a lint rule flagging suspend functions in `:core:*` public APIs that declare `throws` without wrapping.

## 7. Versioning

Internal module contracts are versioned informally via the module's own semantic version in `libs.versions.toml`; a breaking change to any interface listed above requires a corresponding update to every module depending on it in the same PR (enforced by module dependency graph + CI build, not a separate contract test suite in v1.0).
