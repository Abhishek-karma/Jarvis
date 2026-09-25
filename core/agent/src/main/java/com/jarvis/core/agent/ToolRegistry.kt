package com.jarvis.core.agent

import com.jarvis.core.network.ToolDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory runtime tool registry for active, enabled tools.
 * Agnostic of UI and persistence; populated and synchronized via [ToolLoader].
 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool>()
    @Volatile
    private var cachedDefinitions: List<ToolDefinition>? = null

    fun register(tool: Tool) {
        require(!tools.containsKey(tool.name)) { "Tool '${tool.name}' is already registered" }
        tools[tool.name] = tool
        cachedDefinitions = null
    }

    fun registerOrReplace(tool: Tool) {
        tools[tool.name] = tool
        cachedDefinitions = null
    }

    fun unregister(name: String): Boolean {
        val removed = tools.remove(name) != null
        if (removed) cachedDefinitions = null
        return removed
    }

    fun clear() {
        tools.clear()
        cachedDefinitions = null
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /** Wire definitions sent to the LLM as available functions (cached until registry changes). */
    fun definitions(): List<ToolDefinition> {
        val cached = cachedDefinitions
        if (cached != null) return cached
        val fresh = tools.values.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                parametersSchemaJson = tool.parametersSchemaJson,
            )
        }
        cachedDefinitions = fresh
        return fresh
    }
}
