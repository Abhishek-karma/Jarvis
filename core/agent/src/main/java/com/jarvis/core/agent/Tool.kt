package com.jarvis.core.agent

/** Permission tier fixed at registration — the model can never downgrade a tier via prompt content. */
enum class PermissionTier {
    READ_ONLY,
    REVERSIBLE_WRITE,
    SENSITIVE,
    ;

    /** Stable wire/storage name. */
    val wireName: String
        get() =
            when (this) {
                READ_ONLY -> "read_only"
                REVERSIBLE_WRITE -> "reversible_write"
                SENSITIVE -> "sensitive"
            }
}


interface Tool {
    val name: String
    val description: String
    val parametersSchemaJson: String
    val tier: PermissionTier

    suspend fun execute(argsJson: String): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val observationText: String,
    val structuredData: Map<String, Any>? = null,
    val error: String? = null,
)
