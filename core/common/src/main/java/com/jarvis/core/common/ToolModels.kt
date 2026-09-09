package com.jarvis.core.common

/** Source origin of the tool. */
enum class ToolSource {
    BUILTIN,
    CUSTOM,
    IMPORTED,
    MCP,
    ;

    val wireName: String
        get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ToolSource =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.wireName.equals(value, ignoreCase = true) }
                ?: BUILTIN
    }
}

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

    companion object {
        fun fromWire(value: String): PermissionTier =
            when (value.lowercase().trim()) {
                "read_only", "readonly" -> READ_ONLY
                "reversible_write", "action", "reversible" -> REVERSIBLE_WRITE
                "sensitive" -> SENSITIVE
                else -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SENSITIVE
            }
    }
}
