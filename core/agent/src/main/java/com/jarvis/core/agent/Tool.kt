package com.jarvis.core.agent

/** Permission tier fixed at registration (06-AGENT.md §4) — the model can never downgrade a tier via prompt content. */
enum class PermissionTier {
    READ_ONLY,
    REVERSIBLE_WRITE,
    SENSITIVE,
    ;

    /** Stable wire/storage name (09-DATA-MODELS.md §2). */
    val wireName: String
        get() = when (this) {
            READ_ONLY -> "read_only"
            REVERSIBLE_WRITE -> "reversible_write"
            SENSITIVE -> "sensitive"
        }
}

/**
 * One agent-callable capability (10-API-REFERENCE.md §2). `parametersSchemaJson` is a
 * JSON-Schema object serialized as a JSON string — the exact shape forwarded to the LLM
 * as its function definition, so there is no second schema representation to drift.
 */
interface Tool {
    val name: String
    val description: String
    val parametersSchemaJson: String
    val tier: PermissionTier

    suspend fun execute(argsJson: String): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val observationText: String, // fed back into the ReAct loop as the Observation
    val structuredData: Map<String, Any>? = null,
    val error: String? = null,
)
