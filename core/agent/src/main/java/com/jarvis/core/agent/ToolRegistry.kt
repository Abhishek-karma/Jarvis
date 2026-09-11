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

    fun get(name: String): Tool? =
        tools[name] ?: when (name) {
            "web_search", "webSearch", "searchWeb" -> tools["search_web"] ?: tools["web_search"]
            "search_web" -> tools["web_search"] ?: tools["search_web"]
            "fetch_url", "fetchUrl", "url_fetch", "browse_url" -> tools["fetch_url"]
            "calculate", "eval", "evaluate", "calc" -> tools["calculator"]
            "current_time", "get_current_time" -> tools["get_current_datetime"]
            "save_memory", "store_memory", "add_memory", "remember", "store_fact", "set_preference" -> tools["remember_fact"]
            "recall_memory", "get_memories", "get_memory", "list_memories", "search_memory", "search_memories" -> tools["recall_memories"]
            "forget_memory", "delete_memory", "remove_memory" -> tools["forget_fact"]
            "undo", "revert_action", "revert_last_action" -> tools["undo_action"]
            else -> null
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
