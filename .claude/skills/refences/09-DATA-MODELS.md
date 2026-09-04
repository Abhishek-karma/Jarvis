# Data Models

## 1. Room Schema (Conversations & Messages)

```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,             // UUID
    val title: String,
    val createdAt: Long,                    // epoch millis
    val updatedAt: Long,
    val pinned: Boolean = false,
    val providerId: String,                 // active provider for this chat
    val modelId: String,                    // active model for this chat
    val routingOverride: String,            // "auto" | "local" | "cloud"
    val isPrivate: Boolean = false,          // excludes from memory extraction & sync
    val branchedFromConversationId: String?, // nullable, for Feature 1 "branch"
    val branchedFromMessageId: String?
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,                       // "user" | "assistant" | "system" | "tool"
    val content: String,                    // markdown text
    val reasoningContent: String?,           // nullable, Think Mode output
    val createdAt: Long,
    val editedAt: Long?,
    val status: String,                      // "complete" | "streaming" | "stopped" | "error"
    val routeUsed: String?,                  // "local" | model name, per Feature 6 badge
    val toolCallsJson: String?,               // nullable, serialized ToolCall list
    val attachmentsJson: String?,              // nullable, serialized Attachment list
    val promptTokens: Int?,
    val completionTokens: Int?
)
```

## 2. Room Schema (Agent & Audit)

```kotlin
@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val triggerMessageId: String,
    val status: String,                     // "planning" | "running" | "paused_confirmation" | "completed" | "stopped" | "failed"
    val stepCountUsed: Int,
    val startedAt: Long,
    val endedAt: Long?
)

@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val agentRunId: String?,                // nullable — some tool calls happen outside a full agent run
    val toolName: String,
    val tier: String,                       // "read_only" | "reversible_write" | "sensitive"
    val paramsRedactedJson: String,          // sensitive values redacted per 14-SECURITY.md
    val resultStatus: String,                // "success" | "failure" | "cancelled"
    val userConfirmed: Boolean,
    val timestamp: Long
)
```

Audit log rows are append-only at the DAO level (no `@Update`/`@Delete` DAO methods defined for this entity) to keep the log tamper-evident within the app itself.

## 3. Preferences (Proto DataStore)

```protobuf
message UserPreferences {
  int32 schema_version = 1;
  string theme = 2;                  // "dark" | "light" | "system"
  string default_provider_id = 3;
  string default_model_id = 4;
  string think_mode = 5;             // "off" | "on" | "auto"
  string wake_word_phrase = 6;
  int32 voice_silence_timeout_sec = 7;
  bool memory_extraction_enabled = 8;
  bool cautious_mode_agent = 9;      // confirm all tiers
  bool cloud_sync_enabled = 10;
  string agent_step_cap = 11;
}
```

Migrations are explicit `DataMigration<UserPreferences>` implementations keyed by `schema_version`; no in-place field reuse across versions (a removed field's tag number is retired, never reassigned).

## 4. ObjectBox Schema (Vector Store)

```kotlin
@Entity
data class MemoryEmbedding(
    @Id var id: Long = 0,
    @Index var uuid: String = "",             // links back to a MemoryFact row conceptually (no Room FK across DBs)
    var category: String = "",                // "person" | "preference" | "routine" | "date" | "fact"
    var text: String = "",                    // the extracted fact, human-readable
    var sourceConversationId: String = "",
    var createdAt: Long = 0,
    @HnswIndex(dimensions = 384)
    var embedding: FloatArray = FloatArray(0)
)

@Entity
data class SearchIndexEmbedding(
    @Id var id: Long = 0,
    @Index var messageId: String = "",
    var conversationId: String = "",
    @HnswIndex(dimensions = 384)
    var embedding: FloatArray = FloatArray(0)
)
```

Two separate ObjectBox entities/namespaces for memory-fact embeddings vs. conversation-search embeddings, per `03-FEATURES.md` Feature 8 (search results must not be polluted by extracted-fact embeddings).

## 5. Cross-Store Consistency

- Room (SQL) and ObjectBox (vector) are separate database files with no foreign-key enforcement between them. Consistency is maintained application-side: deleting a `ConversationEntity` (cascades to its messages via Room FK) also triggers a domain-layer cleanup call that removes matching `SearchIndexEmbedding` rows and any `MemoryEmbedding` rows whose `sourceConversationId` matches, run as a `WorkManager` job so the UI delete isn't blocked on vector-store I/O.
- This is a documented eventual-consistency window (typically sub-second) rather than a transactional guarantee — acceptable since orphaned embeddings only affect search/memory relevance, not data correctness or user-visible chat content.

## 6. Attachment & ToolCall DTOs (serialized as JSON columns above)

```kotlin
data class Attachment(val id: String, val type: String, val uri: String, val mimeType: String, val sizeBytes: Long)
data class ToolCall(val id: String, val toolName: String, val argsJson: String, val resultJson: String?, val status: String)
```
