package com.jarvis.core.network

import com.jarvis.core.common.Message
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.common.ThinkMode
import kotlinx.coroutines.flow.Flow

/** Provider capability flags. */
data class ProviderCapabilities(
    val vision: Boolean = false,
    val maxContext: Int = 128_000,
    val supportsTools: Boolean = false,
    val supportsReasoning: Boolean = false,
)


data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
)


data class ChatRequest(
    val conversationHistory: List<Message>,
    val systemPrompt: String? = null,
    val model: String,
    val thinkMode: ThinkMode = ThinkMode.AUTO,
    val reasoningRequested: Boolean = false,
    val toolsAvailable: List<ToolDefinition>? = null,
)

/** Normalized streaming contract — one sealed class for every adapter. */
sealed class ChatStreamEvent {
    data class TokenDelta(
        val text: String,
    ) : ChatStreamEvent()

    data class ReasoningDelta(
        val text: String,
    ) : ChatStreamEvent()

    /** Emitted only when the provider supports tools (capabilities.supportsTools). */
    data class ToolCallRequested(
        val name: String,
        val argsJson: String,
    ) : ChatStreamEvent()

    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
    ) : ChatStreamEvent()

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : ChatStreamEvent()

    data object Done : ChatStreamEvent()
}


interface LlmProvider {
    val id: String
    val capabilities: ProviderCapabilities

    suspend fun listModels(): Result<List<ModelInfo>>

    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent>

    /** Aborts in-flight streams and releases socket resources — invoked on cancellation. */
    fun close()
}
