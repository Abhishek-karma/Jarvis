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

    fun get(name: String): Tool? {
        tools[name]?.let { return it }
        val canonical = when (name) {
            "web_search", "webSearch", "searchWeb" -> "search_web"
            "fetch_url", "fetchUrl", "url_fetch", "browse_url" -> "fetch_url"
            "calculate", "eval", "evaluate", "calc" -> "calculator"
            "current_time", "get_current_time" -> "get_current_datetime"
            "save_memory", "store_memory", "add_memory", "remember", "store_fact", "set_preference" -> "remember_fact"
            "recall_memory", "get_memories", "get_memory", "list_memories", "search_memory", "search_memories" -> "recall_memories"
            "forget_memory", "delete_memory", "remove_memory" -> "forget_fact"
            "undo", "revert_action", "revert_last_action" -> "undo_action"
            else -> name
        }
        return if (canonical == name) null else tools[canonical]
    }

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
