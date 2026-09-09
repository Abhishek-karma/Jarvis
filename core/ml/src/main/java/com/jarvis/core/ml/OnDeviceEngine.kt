package com.jarvis.core.ml

import com.jarvis.core.common.Message
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.ToolDefinition
import kotlinx.coroutines.flow.Flow

interface OnDeviceEngine : AutoCloseable {

    /**
     * Native chat stream supporting conversation history, system prompt, and tools.
     * Yields normalized [ChatStreamEvent] (TokenDelta, ToolCallRequested, Done, Error).
     */
    fun streamChat(
        conversationHistory: List<Message>,
        systemPrompt: String? = null,
        tools: List<ToolDefinition>? = null,
        temperature: Double? = null,
    ): Flow<ChatStreamEvent>

    /** [temperature] overrides the engine's default sampler when non-null. */
    suspend fun generate(
        prompt: String,
        temperature: Double? = null,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    )

    override fun close()
}

