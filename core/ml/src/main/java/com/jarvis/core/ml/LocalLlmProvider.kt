package com.jarvis.core.ml

import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
import kotlinx.coroutines.flow.Flow

class LocalLlmProvider(
    override val id: String,
    private val spec: LocalModelSpec,
    private val engine: OnDeviceEngine,
) : LlmProvider {
    /** ChatRequest.model value for local requests (informational — the prompt is single-shot). */
    val modelId: String = spec.id

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            vision = false,
            maxContext = spec.contextLength,
            supportsTools = true,
            supportsReasoning = false,
        )

    override suspend fun listModels(): Result<List<ModelInfo>> =
        Result.success(listOf(ModelInfo(id = spec.id, displayName = spec.displayName)))

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> {
        val effectiveSystemPrompt =
            if (request.systemPrompt.isNullOrBlank() && request.toolsAvailable.isNullOrEmpty()) {
                DEFAULT_SYSTEM_PROMPT
            } else {
                request.systemPrompt
            }

        val agentMode = !request.toolsAvailable.isNullOrEmpty()
        val temperature = if (agentMode) AGENT_TEMPERATURE else CHAT_TEMPERATURE

        return engine.streamChat(
            conversationHistory = request.conversationHistory,
            systemPrompt = effectiveSystemPrompt,
            tools = request.toolsAvailable,
            temperature = temperature,
        )
    }

    override fun close() {
    }

    private companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Jarvis, a helpful and friendly AI assistant running fully on-device. " +
                "Answer clearly and concisely."
        const val AGENT_TEMPERATURE = 0.2
        const val CHAT_TEMPERATURE = 0.7
    }
}

