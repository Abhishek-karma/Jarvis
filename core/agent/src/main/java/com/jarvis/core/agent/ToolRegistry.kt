package com.jarvis.core.agent

import com.jarvis.core.network.ToolDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory runtime tool registry for active, enabled tools.
 * Agnostic of UI and persistence; populated and synchronized via [ToolLoader].
 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool>()

    fun register(tool: Tool) {
        require(!tools.containsKey(tool.name)) { "Tool '${tool.name}' is already registered" }
        tools[tool.name] = tool
    }

    fun registerOrReplace(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String): Boolean {
        return tools.remove(name) != null
    }

    fun clear() {
        tools.clear()
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    fun names(): Set<String> = tools.keys.toSet()

    fun size(): Int = tools.size

    /** Wire definitions sent to the LLM as available functions. */
    fun definitions(): List<ToolDefinition> =
        tools.values.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                parametersSchemaJson = tool.parametersSchemaJson,
            )
        }
}
