package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult

/**
 * File search (v0.5 catalog, `06-AGENT §3`): find files on the device by name. Read-only.
 * The MediaStore reader is injected as a lambda so the tool is JVM-unit-testable;
 * AgentModule binds the platform index query behind it.
 */
object FilesTools {
    const val SEARCH_FILES = "search_files"

    val manifestNames: List<String> = listOf(SEARCH_FILES)

    /** One file hit from the device's media index. */
    data class FileHit(
        val displayName: String,
        val path: String,
        val sizeBytes: Long,
        val modifiedUtcMillis: Long,
    )

    fun all(
        search: suspend (query: String) -> Result<List<FileHit>>,
    ): List<Tool> = listOf(searchFiles(search))

    fun searchFiles(search: suspend (String) -> Result<List<FileHit>>): Tool =
        object : Tool {
            override val name = SEARCH_FILES
            override val description =
                "Search the device's file index for files whose name contains the given text " +
                    "(documents, downloads, media). Read-only; returns names and locations."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = SEARCH_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val query = args.string("query")
                if (query.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: query is required.",
                        error = "query is required",
                    )
                }
                return search(query.trim()).fold(
                    onSuccess = { hits ->
                        when {
                            hits.isEmpty() ->
                                ToolResult(
                                    success = true,
                                    observationText = "No files found matching \"$query\".",
                                    structuredData = mapOf("count" to 0),
                                )

                            else -> {
                                val lines =
                                    hits.take(MAX_MATCHES).joinToString("\n") { hit ->
                                        "- ${hit.displayName} (${formatSize(hit.sizeBytes)})" +
                                            (if (hit.path.isBlank()) "" else " — ${hit.path}")
                                    }
                                ToolResult(
                                    success = true,
                                    observationText =
                                        "${hits.size} file(s) matching \"$query\":\n$lines" +
                                            if (hits.size > MAX_MATCHES) "\n(truncated)" else "",
                                    structuredData = mapOf("count" to hits.size),
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not search files.",
                            error = error.message ?: "File search failed",
                        )
                    },
                )
            }
        }

    internal const val MAX_MATCHES = 10

    internal fun formatSize(bytes: Long): String =
        when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }

    private const val SEARCH_SCHEMA =
        """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
}
