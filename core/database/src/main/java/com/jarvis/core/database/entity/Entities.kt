package com.jarvis.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Conversations table. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val providerId: String,
    val modelId: String,
    val routingOverride: String,
    val isPrivate: Boolean = false,
    val branchedFromConversationId: String? = null,
    val branchedFromMessageId: String? = null,
)

/** Messages table with cascade delete on conversation. */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val reasoningContent: String? = null,
    val createdAt: Long,
    val editedAt: Long? = null,
    val status: String,
    val routeUsed: String? = null,
    val errorHint: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/** User-configured provider rows; API keys NEVER live here. */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val model: String? = null,
    val type: String,
    val isDefault: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Append-only audit row for agent tool executions. */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val agentRunId: String?,
    val toolName: String,
    val tier: String,
    val paramsRedactedJson: String,
    val resultStatus: String,
    val userConfirmed: Boolean,
    val timestamp: Long,
)

/** Explicit memory row across conversation context, long-term facts, and episodic events. */
@Entity(
    tableName = "memories",
    indices = [Index("category"), Index("timestamp"), Index("isPrivate")],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val category: String, // "CONVERSATION_CONTEXT", "LONG_TERM_FACT", "EPISODIC"
    val content: String,
    val source: String, // conversationId, tool, user
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val isPrivate: Boolean = false,
    val isActive: Boolean = true,
)

/** Durable task state machine record. */
@Entity(
    tableName = "tasks",
    indices = [Index("state"), Index("createdAt")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val goal: String,
    val triggerType: String, // "MANUAL", "SCHEDULED", "ROUTINE"
    val triggerConfigJson: String? = null,
    val state: String, // "SCHEDULED", "QUEUED", "RUNNING", "WAITING_FOR_CONFIRMATION", "COMPLETED", "FAILED", "CANCELLED"
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

/** Scheduled automations & routines record. */
@Entity(
    tableName = "routines",
    indices = [Index("enabled"), Index("nextRunAt")],
)
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val goal: String,
    val scheduleType: String, // "ONE_TIME", "RECURRING"
    val cronOrInterval: String,
    val nextRunAt: Long? = null,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastRunStatus: String? = null,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Reversible write actions for undo capabilities. */
@Entity(
    tableName = "reversible_actions",
    indices = [Index("createdAt"), Index("isReverted")],
)
data class ReversibleActionEntity(
    @PrimaryKey val id: String,
    val actionType: String,
    val target: String,
    val inverseActionJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isReverted: Boolean = false,
)

/**
 * Durable per-task operation ledger. Each side effect the agent issues (with an
 * idempotency key) gets a row here, so a crash between "tool ran" and "tool was
 * recorded" cannot cause the same effect to run twice on retry. Supersedes the
 * previous `Task.stepsJson` substring check, which silently miss-matched partial
 * JSON fragments.
 */
@Entity(
    tableName = "operations",
    indices = [Index("taskId"), Index(value = ["idempotencyKey"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = TaskEntity::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class OperationEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val toolName: String,
    val idempotencyKey: String,
    val status: String, // EXECUTING | SUCCEEDED | FAILED
    val resultJson: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Local diagnostic trace for inspecting slow/failed requests without cloud telemetry. */
@Entity(
    tableName = "request_diagnostics",
    indices = [Index("timestamp"), Index("providerId"), Index("route")],
)
data class RequestDiagnosticsEntity(
    @PrimaryKey val id: String,
    val requestId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val providerId: String,
    val model: String,
    val route: String,
    val latencyMs: Long,
    val firstTokenLatencyMs: Long? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val retries: Int = 0,
    val fallbacks: String = "",
    val toolCount: Int = 0,
    val failureClass: String? = null,
    val errorMessage: String? = null,
)

/** Persistent catalog of tools installed, imported, or configured in Jarvis. */
@Entity(
    tableName = "tool_catalog",
    indices = [Index("source"), Index("enabled")],
)
data class ToolCatalogEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
    val tier: String, // "READ_ONLY", "REVERSIBLE_WRITE", "SENSITIVE"
    val source: String, // "BUILTIN", "CUSTOM", "IMPORTED", "MCP"
    val version: String = "1.0.0",
    val enabled: Boolean = true,
    val configJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

