package com.jarvis.core.agent

import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.common.PermissionTier as CommonPermissionTier
import com.jarvis.core.common.ToolSource as CommonToolSource

    typealias PermissionTier = CommonPermissionTier
    typealias ToolSource = CommonToolSource

    /** Canonical tool execution contract in Jarvis. */
    interface Tool {
    val name: String
    val description: String
    val parametersSchemaJson: String
    val tier: PermissionTier
    val source: ToolSource get() = ToolSource.BUILTIN
    val version: String get() = "1.0.0"
    val enabled: Boolean get() = true

    suspend fun execute(argsJson: String): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val observationText: String,
    val structuredData: Map<String, Any>? = null,
    val error: String? = null,
    val errorCode: ErrorCode? = null,
) {
    val resolvedErrorCode: ErrorCode
        get() = errorCode ?: ErrorCode.fromString(error)
}
