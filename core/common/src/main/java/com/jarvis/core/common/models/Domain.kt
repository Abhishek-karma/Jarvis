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



    val toolCallId: String? = null,
    val toolCallName: String? = null,
    val toolCallArgsJson: String? = null,
)


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


enum class ThinkMode { OFF, ON, AUTO }


enum class ProviderType {
    /** OpenAI HTTP/SSE wire format. Covers OpenAI, Groq, Mistral, xAI and compatible gateways. */
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

val DEFAULT_NARA_PROVIDER = ProviderConfig(
    id = "nara",
    name = "Nara",
    baseUrl = "https://router.bynara.id/v1",
    model = "laguna-s-2.1",
    type = ProviderType.OPENAI_COMPATIBLE,
    isDefault = true,
)

const val DEFAULT_NARA_API_KEY = "sk-nry-9n9ode5y72y__1k_yJ_b80QbpASRc5mD7r20y7FGIxI"

data class ModelInfo(
    val id: String,
    val displayName: String,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
)

/** Explicit memory categories. */
enum class MemoryCategory {
    CONVERSATION_CONTEXT,
    LONG_TERM_FACT,
    EPISODIC,
}

data class Memory(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,
    val content: String,
    val source: String,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val isPrivate: Boolean = false,
    val isActive: Boolean = true,
)

/** Durable task states. */
enum class TaskState {
    SCHEDULED,
    QUEUED,
    RUNNING,
    WAITING_FOR_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class TaskTriggerType {
    MANUAL,
    SCHEDULED,
    ROUTINE,
}

data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val goal: String,
    val triggerType: TaskTriggerType = TaskTriggerType.MANUAL,
    val triggerConfigJson: String? = null,
    val state: TaskState = TaskState.QUEUED,
    val stepsJson: String = "[]",
    val requiredPermissionsJson: String = "[]",
    val retries: Int = 0,
    val maxRetries: Int = 3,
    val resultJson: String? = null,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
)

enum class RoutineScheduleType {
    ONE_TIME,
    RECURRING,
}

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val goal: String,
    val scheduleType: RoutineScheduleType = RoutineScheduleType.RECURRING,
    val cronOrInterval: String,
    val nextRunAt: Long? = null,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastRunStatus: String? = null,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class ReversibleAction(
    val id: String = UUID.randomUUID().toString(),
    val actionType: String,
    val target: String,
    val inverseActionJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isReverted: Boolean = false,
)

/** Lifecycle of a recorded side effect. Persisted in the [Operation] ledger. */
enum class OperationStatus {
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    companion object {
        fun fromString(value: String): OperationStatus =
            runCatching { valueOf(value.uppercase()) }.getOrDefault(UNKNOWN)
    }
}

/** A single recorded side effect, keyed by the call's idempotency key. */
data class Operation(
    val id: String,
    val taskId: String,
    val toolName: String,
    val idempotencyKey: String,
    val status: OperationStatus,
    val resultJson: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
