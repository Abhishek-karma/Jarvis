package com.jarvis.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Mirrors 09-DATA-MODELS.md §1 — conversations table. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val providerId: String,
    val modelId: String,
    val routingOverride: String, // "auto" | "local" | "cloud"
    val isPrivate: Boolean = false,
    val branchedFromConversationId: String? = null,
    val branchedFromMessageId: String? = null,
)

/** Mirrors 09-DATA-MODELS.md §1 — messages table with cascade delete on conversation. */
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
    val role: String, // "user" | "assistant" | "system" | "tool"
    val content: String,
    val reasoningContent: String? = null,
    val createdAt: Long,
    val editedAt: Long? = null,
    val status: String, // "complete" | "streaming" | "stopped" | "error"
    val routeUsed: String? = null,
    val errorHint: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/** User-configured provider rows; API keys NEVER live here (14-SECURITY.md §2). */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val type: String, // "openai_compatible"
    val isDefault: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Append-only audit row for agent tool executions (09-DATA-MODELS.md §2, 14-SECURITY.md §7). */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val agentRunId: String?, // nullable — some tool calls happen outside a full agent run
    val toolName: String,
    val tier: String, // "read_only" | "reversible_write" | "sensitive"
    val paramsRedactedJson: String, // sensitive values redacted per 14-SECURITY.md
    val resultStatus: String, // "success" | "failure" | "cancelled"
    val userConfirmed: Boolean,
    val timestamp: Long,
)

