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

    fun get(name: String): Tool? {
        tools[name]?.let { return it }
        val canonical = CANONICAL_ALIASES[name] ?: return null
        return tools[canonical]
    }

    companion object {
        private val CANONICAL_ALIASES: Map<String, String> = mapOf(
            "web_search" to "search_web",
            "webSearch" to "search_web",
            "searchWeb" to "search_web",
            "fetchUrl" to "fetch_url",
            "url_fetch" to "fetch_url",
            "browse_url" to "fetch_url",
            "calculate" to "calculator",
            "eval" to "calculator",
            "evaluate" to "calculator",
            "calc" to "calculator",
            "current_time" to "get_current_datetime",
            "get_current_time" to "get_current_datetime",
            "save_memory" to "remember_fact",
            "store_memory" to "remember_fact",
            "add_memory" to "remember_fact",
            "remember" to "remember_fact",
            "store_fact" to "remember_fact",
            "set_preference" to "remember_fact",
            "recall_memory" to "recall_memories",
            "get_memories" to "recall_memories",
            "get_memory" to "recall_memories",
            "list_memories" to "recall_memories",
            "search_memory" to "recall_memories",
            "search_memories" to "recall_memories",
            "forget_memory" to "forget_fact",
            "delete_memory" to "forget_fact",
            "remove_memory" to "forget_fact",
            "undo" to "undo_action",
            "revert_action" to "undo_action",
            "revert_last_action" to "undo_action",
        )
    }

    fun all(): List<Tool> = tools.values.toList()

    fun names(): Set<String> = tools.keys.toSet()

    fun size(): Int = tools.size

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
