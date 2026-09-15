package com.jarvis.core.network

import kotlinx.coroutines.flow.Flow

/**
 * Stateful session interface allowing an agent run to communicate with the SAME conversation
 * across multiple turns (Initial -> Model Tool Call -> Tool Result -> Final Answer).
 */
interface AgentChatSession : AutoCloseable {
    fun sendInitial(): Flow<ChatStreamEvent>
    fun sendToolResponses(responses: List<ToolResponsePayload>): Flow<ChatStreamEvent>
}
