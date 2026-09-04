package com.jarvis.core.agent

import com.jarvis.core.network.ToolDefinition

/**
 * Single registration point for every tool — built-in, MCP-derived, or OpenAPI-imported
 * alike (06-AGENT.md §2). The ReAct loop never special-cases a tool's origin.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        require(!tools.containsKey(tool.name)) { "Tool '${tool.name}' is already registered" }
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /** Wire definitions sent to the LLM as available functions (10-API-REFERENCE.md §1). */
    fun definitions(): List<ToolDefinition> = tools.values.map { tool ->
        ToolDefinition(
            name = tool.name,
            description = tool.description,
            parametersSchemaJson = tool.parametersSchemaJson,
        )
    }
}
