package com.jarvis.core.ml

import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.AgentChatSession
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
import com.jarvis.core.network.ToolResponsePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
            supportsTools = spec.supportsTools,
            supportsReasoning = spec.supportsReasoning,
            supportsSessions = spec.supportsTools,
        )

    override suspend fun listModels(): Result<List<ModelInfo>> =
        Result.success(
            listOf(
                ModelInfo(
                    id = spec.id,
                    displayName = spec.displayName,
                    supportsReasoning = spec.supportsReasoning,
                ),
            ),
        )

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> {
        val effectiveSystemPrompt =
            if (request.systemPrompt.isNullOrBlank() && request.toolsAvailable.isNullOrEmpty()) {
                DEFAULT_SYSTEM_PROMPT
            } else {
                request.systemPrompt
            }

        val agentMode = !request.toolsAvailable.isNullOrEmpty()
        val temperature = if (agentMode) AGENT_TEMPERATURE else CHAT_TEMPERATURE

        val upstream =
            engine.streamChat(
                conversationHistory = request.conversationHistory,
                systemPrompt = effectiveSystemPrompt,
                tools = request.toolsAvailable,
                temperature = temperature,
            )

        if (!agentMode) {
            return flow {
                var sawToolCall = false
                upstream.collect { event ->
                    if (event is ChatStreamEvent.ToolCallRequested) {
                        sawToolCall = true
                    } else {
                        emit(event)
                    }
                }
                if (sawToolCall) {
                    emit(
                        ChatStreamEvent.Error(
                            code = "local_protocol",
                            message = "On-device model returned a tool call outside an agent run. " +
                                "Retry with an action request (agent mode) so the tool can be validated and executed safely.",
                            retryable = false,
                        ),
                    )
                }
            }
        }
        return upstream
    }

    override fun startSession(request: ChatRequest): AgentChatSession? {
        val effectiveSystemPrompt =
            if (request.systemPrompt.isNullOrBlank() && request.toolsAvailable.isNullOrEmpty()) {
                DEFAULT_SYSTEM_PROMPT
            } else {
                request.systemPrompt
            }

        val onDeviceSession = engine.startSession(
            conversationHistory = request.conversationHistory,
            systemPrompt = effectiveSystemPrompt,
            tools = request.toolsAvailable,
            temperature = AGENT_TEMPERATURE,
        ) ?: return null

        return object : AgentChatSession {
            override fun sendInitial(): Flow<ChatStreamEvent> = onDeviceSession.sendInitial()

            override fun sendToolResponses(responses: List<ToolResponsePayload>): Flow<ChatStreamEvent> =
                onDeviceSession.sendToolResponses(responses)

            override fun close() {
                onDeviceSession.close()
            }
        }
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

