package com.jarvis.core.common

import java.util.UUID

/** Domain-level chat roles, stable across Room persistence and provider wire formats. */
enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

/** Lifecycle of a message row. */
enum class MessageStatus { COMPLETE, STREAMING, STOPPED, ERROR }

/** Domain model for a single chat message (UI-facing, provider-agnostic). */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val reasoningContent: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val editedAt: Long? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val routeUsed: String? = null,
    val errorHint: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    // Tool-call turns (agent mode, v0.5). An ASSISTANT message requesting a tool carries
    // toolCallId + toolCallName + toolCallArgsJson; the paired TOOL message carries the
    // observation with the same toolCallId so adapters can serialize both wire dialects.
    val toolCallId: String? = null,
    val toolCallName: String? = null,
    val toolCallArgsJson: String? = null,
)

/**
 * Title given to a conversation at creation. An untitled conversation is auto-named from
 * its first user message (see ChatViewModel); manual renames replace it for good.
 */
const val DEFAULT_CONVERSATION_TITLE = "New chat"

/** Domain model for a conversation. */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_CONVERSATION_TITLE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val providerId: String = "",
    val modelId: String = "",
    val routingOverride: RoutingOverride = RoutingOverride.AUTO,
    val isPrivate: Boolean = false,
    val branchedFromConversationId: String? = null,
    val branchedFromMessageId: String? = null,
)

enum class RoutingOverride { AUTO, LOCAL, CLOUD }

/**
 * Reasoning-effort setting for models that expose a thinking/reasoning channel.
 * OFF never requests reasoning, ON always does, AUTO derives it per message via the
 * ThinkModeHeuristic (math/code, creative-writing, and explicit step-by-step asks → ON).
 * Persisted in user preferences; this enum is the source of truth for the wire-level
 * `reasoningRequested` flag on chat requests.
 */
enum class ThinkMode { OFF, ON, AUTO }

/**
 * Provider families the settings screen can create. Each value maps to one [LlmProvider]
 * adapter wired through [com.jarvis.core.network.ProviderManager.adapterFor].
 *
 * Older persisted [ProviderConfig]s deserialise to [OPENAI_COMPATIBLE] by default, which
 * preserves v0.1-v0.5 user data on upgrade.
 */
enum class ProviderType {
    /** OpenAI HTTP/SSE wire format. Covers OpenAI, Groq, Mistral, xAI, LM Studio, Ollama. */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages API + SSE event-stream format. */
    ANTHROPIC,

    /** Google Gemini generateContent + streamGenerateContent. */
    GEMINI,
}

/** User-configured provider instance (credentials live in EncryptedSharedPreferences, never here). */
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    /** Optional model id used for new chats; null = pick the provider's first model. */
    val model: String? = null,
    val type: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    val isDefault: Boolean = false,
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
)
