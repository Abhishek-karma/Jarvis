package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.database.repository.MemoryRepository
import java.util.UUID

object MemoryTools {
    const val REMEMBER_FACT = "remember_fact"
    const val RECALL_MEMORIES = "recall_memories"
    const val FORGET_FACT = "forget_fact"

    val manifestNames: List<String> = listOf(REMEMBER_FACT, RECALL_MEMORIES, FORGET_FACT)

    private const val REMEMBER_SCHEMA = """{
  "type": "object",
  "properties": {
    "content": {"type": "string", "description": "The fact, preference, or event to remember."},
    "category": {"type": "string", "enum": ["CONVERSATION_CONTEXT", "LONG_TERM_FACT", "EPISODIC"], "description": "The memory category. Defaults to LONG_TERM_FACT."},
    "is_private": {"type": "boolean", "description": "Whether this memory is sensitive/private (never shared with cloud models)."}
  },
  "required": ["content"]
}"""

    private const val RECALL_SCHEMA = """{
  "type": "object",
  "properties": {
    "category": {"type": "string", "enum": ["CONVERSATION_CONTEXT", "LONG_TERM_FACT", "EPISODIC"], "description": "Optional category to filter memories by."}
  }
}"""

    private const val FORGET_SCHEMA = """{
  "type": "object",
  "properties": {
    "memory_id": {"type": "string", "description": "ID of the memory to forget."},
    "query": {"type": "string", "description": "Keyword or query to match and forget if ID is not known."}
  }
}"""

    fun all(repository: MemoryRepository): List<Tool> = listOf(
        rememberFact(repository),
        recallMemories(repository),
        forgetFact(repository),
    )

    fun rememberFact(repository: MemoryRepository): Tool =
        object : Tool {
            override val name = REMEMBER_FACT
            override val description = "Stores a fact, user preference, or episodic event into persistent assistant memory."
            override val parametersSchemaJson = REMEMBER_SCHEMA
            override val tier = PermissionTier.REVERSIBLE_WRITE

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(success = false, observationText = "Invalid arguments", error = "Malformed JSON")
                val content = args.string("content")
                    ?: return ToolResult(success = false, observationText = "Missing 'content'", error = "content is required")
                val categoryStr = args.string("category") ?: "LONG_TERM_FACT"
                val category = runCatching { MemoryCategory.valueOf(categoryStr.uppercase()) }
                    .getOrDefault(MemoryCategory.LONG_TERM_FACT)
                val isPrivate = args.boolean("is_private") ?: false

                val memory = Memory(
                    id = UUID.randomUUID().toString(),
                    category = category,
                    content = content,
                    source = "agent_interaction",
                    confidence = 1.0f,
                    timestamp = System.currentTimeMillis(),
                    isPrivate = isPrivate,
                    isActive = true,
                )
                repository.upsert(memory)
                return ToolResult(
                    success = true,
                    observationText = "Saved memory [${memory.id}] in category ${category.name} (private: $isPrivate): \"$content\"",
                    structuredData = mapOf("id" to memory.id, "category" to category.name, "is_private" to isPrivate),
                )
            }
        }

    fun recallMemories(repository: MemoryRepository): Tool =
        object : Tool {
            override val name = RECALL_MEMORIES
            override val description = "Retrieves active stored memories, optionally filtered by category."
            override val parametersSchemaJson = RECALL_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val categoryStr = args?.string("category")
                val category = categoryStr?.let {
                    runCatching { MemoryCategory.valueOf(it.uppercase()) }.getOrNull()
                }

                val allMemories = repository.getActive()
                val filtered = if (category != null) {
                    allMemories.filter { it.category == category }
                } else {
                    allMemories
                }

                if (filtered.isEmpty()) {
                    return ToolResult(
                        success = true,
                        observationText = "No memories stored" + (if (category != null) " for category ${category.name}." else "."),
                        structuredData = mapOf("count" to 0),
                    )
                }

                val summary = filtered.joinToString("\n") {
                    "- [${it.id}] (${it.category}, private=${it.isPrivate}): ${it.content}"
                }
                return ToolResult(
                    success = true,
                    observationText = "Found ${filtered.size} memories:\n$summary",
                    structuredData = mapOf("count" to filtered.size),
                )
            }
        }

    fun forgetFact(repository: MemoryRepository): Tool =
        object : Tool {
            override val name = FORGET_FACT
            override val description = "Deletes or deactivates a memory by ID or matching keyword."
            override val parametersSchemaJson = FORGET_SCHEMA
            override val tier = PermissionTier.REVERSIBLE_WRITE

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(success = false, observationText = "Invalid arguments", error = "Malformed JSON")
                val memoryId = args.string("memory_id")
                val query = args.string("query")

                if (memoryId != null) {
                    val existing = repository.get(memoryId)
                    if (existing != null) {
                        repository.delete(memoryId)
                        return ToolResult(
                            success = true,
                            observationText = "Deleted memory [${existing.id}]: \"${existing.content}\"",
                            structuredData = mapOf("deletedId" to memoryId),
                        )
                    } else {
                        return ToolResult(
                            success = false,
                            observationText = "Memory with ID $memoryId not found.",
                            error = "Not found",
                        )
                    }
                }

                if (query != null) {
                    val active = repository.getActive()
                    val matches = active.filter { it.content.contains(query, ignoreCase = true) }
                    if (matches.isEmpty()) {
                        return ToolResult(
                            success = false,
                            observationText = "No active memories matched query '$query'.",
                            error = "No match",
                        )
                    }
                    matches.forEach { repository.delete(it.id) }
                    return ToolResult(
                        success = true,
                        observationText = "Forgot ${matches.size} memories matching '$query'.",
                        structuredData = mapOf("deletedCount" to matches.size),
                    )
                }

                return ToolResult(
                    success = false,
                    observationText = "Must specify either 'memory_id' or 'query'.",
                    error = "Missing arguments",
                )
            }
        }
}
