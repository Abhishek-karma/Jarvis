package com.jarvis.core.ml

import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
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
        if (!agentMode) return upstream

        // Agent mode: buffer text deltas so a text-embedded [[{"name":...,"args":{...}}]] call
        // is converted into a ToolCallRequested event instead of leaking raw markup into the
        // chat as the assistant's visible reply. Native tool calls pass through (deduplicated).
        return flow {
            val textBuffer = StringBuilder()
            val emittedToolKeys = mutableSetOf<String>()
            var failed = false
            upstream.collect { event ->
                when (event) {
                    is ChatStreamEvent.ToolCallRequested -> {
                        if (emittedToolKeys.add(event.name + "\n" + event.argsJson)) emit(event)
                    }
                    is ChatStreamEvent.TokenDelta -> textBuffer.append(event.text)
                    is ChatStreamEvent.Error -> {
                        failed = true
                        emit(event)
                    }
                    is ChatStreamEvent.Done -> Unit // re-emitted below, after text parsing
                    is ChatStreamEvent.ReasoningDelta, is ChatStreamEvent.Usage -> emit(event)
                }
            }
            if (failed) return@flow
            val fullText = textBuffer.toString()
            val prose = ToolCallParser.stripToolCalls(fullText).trim()
            if (prose.isNotEmpty()) emit(ChatStreamEvent.TokenDelta(prose))
            for (call in ToolCallParser.parseAll(fullText)) {
                if (emittedToolKeys.add(call.name + "\n" + call.argsJson)) {
                    emit(ChatStreamEvent.ToolCallRequested(name = call.name, argsJson = call.argsJson))
                }
            }
            emit(ChatStreamEvent.Done)
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

