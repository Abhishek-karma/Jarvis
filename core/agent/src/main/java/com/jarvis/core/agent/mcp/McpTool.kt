package com.jarvis.core.agent.mcp

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.ToolSource

/**
 * Result returned by an external MCP (Model Context Protocol) tool execution.
 */
data class McpToolResponse(
    val content: String,
    val isError: Boolean = false,
)

/**
 * Communication client for dispatching tool calls to a local or remote MCP server.
 */
fun interface McpClient {
    suspend fun callTool(name: String, argumentsJson: String): McpToolResponse
}

/**
 * Adapter exposing a Model Context Protocol (MCP) tool as a standard Jarvis [Tool].
 */
class McpTool(
    override val name: String,
    override val description: String,
    override val parametersSchemaJson: String,
    override val tier: PermissionTier = PermissionTier.SENSITIVE,
    override val source: ToolSource = ToolSource.MCP,
    override val version: String = "1.0.0",
    override val enabled: Boolean = true,
    private val client: McpClient,
) : Tool {

    override suspend fun execute(argsJson: String): ToolResult {
        return runCatching {
            val response = client.callTool(name, argsJson)
            if (response.isError) {
                ToolResult(
                    success = false,
                    observationText = "MCP Tool Error ($name): ${response.content}",
                    error = response.content,
                )
            } else {
                ToolResult(
                    success = true,
                    observationText = response.content,
                )
            }
        }.getOrElse { err ->
            ToolResult(
                success = false,
                observationText = "MCP Communication Error ($name): ${err.message}",
                error = err.message,
            )
        }
    }
}
