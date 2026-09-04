package com.jarvis.core.network

import com.jarvis.core.common.Message
import com.jarvis.core.common.ModelInfo
import kotlinx.coroutines.flow.Flow

/** Provider capability flags (05-LLM-PROVIDERS.md §2). */
data class ProviderCapabilities(
    val vision: Boolean = false,
    val maxContext: Int = 128_000,
    val supportsTools: Boolean = false,
    val supportsReasoning: Boolean = false,
    val supportsRealtimeVoice: Boolean = false,
)

/**
 * Wire-level tool description sent to the LLM as an available function
 * (10-API-REFERENCE.md §1). `parametersSchemaJson` is a JSON-Schema object serialized as a
 * JSON string; it is produced by :core:agent from its typed [com.jarvis.core.agent.JsonSchema].
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
)

/**
 * Normalized chat request (05-LLM-PROVIDERS.md §2). v0.1 carried only text history + system
 * prompt; `toolsAvailable` arrives with agent mode (v0.5) and is null when not in agent mode.
 */
data class ChatRequest(
    val conversationHistory: List<Message>,
    val systemPrompt: String? = null,
    val model: String,
    val reasoningRequested: Boolean = false,
    val toolsAvailable: List<ToolDefinition>? = null,
)

/** Normalized streaming contract (05-LLM-PROVIDERS.md §4) — one sealed class for every adapter. */
sealed class ChatStreamEvent {
    data class TokenDelta(val text: String) : ChatStreamEvent()
    data class ReasoningDelta(val text: String) : ChatStreamEvent()

    /** Emitted only when the provider supports tools (capabilities.supportsTools). */
    data class ToolCallRequested(val name: String, val argsJson: String) : ChatStreamEvent()
    data class Usage(val promptTokens: Int, val completionTokens: Int) : ChatStreamEvent()
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : ChatStreamEvent()

    data object Done : ChatStreamEvent()
}

/**
 * Every provider — cloud or local — implements this single interface so the rest of the app
 * never branches on provider identity (05-LLM-PROVIDERS.md §2, 10-API-REFERENCE.md §1).
 */
interface LlmProvider {
    val id: String
    val capabilities: ProviderCapabilities

    suspend fun listModels(): Result<List<ModelInfo>>

    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent>

    /** Aborts in-flight streams and releases socket resources — invoked on cancellation. */
    fun close()
}
