package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier


object FilesTools {
    const val SEARCH_FILES = "search_files"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"

    val manifestNames: List<String> = listOf(SEARCH_FILES, READ_FILE, WRITE_FILE)

    /** One file hit from the device's media index. */
    data class FileHit(
        val displayName: String,
        val path: String,
        val sizeBytes: Long,
        val modifiedUtcMillis: Long,
    )

    fun all(
        search: suspend (query: String) -> Result<List<FileHit>>,
        read: (suspend (path: String) -> Result<String>)? = null,
        write: (suspend (path: String, content: String, append: Boolean) -> Result<Unit>)? = null,
    ): List<Tool> = buildList {
        add(searchFiles(search))
        if (read != null) add(readFile(read))
        if (write != null) add(writeFile(write))
    }

    fun readFile(read: suspend (String) -> Result<String>): Tool =
        object : Tool {
            override val name = READ_FILE
            override val description =
                "Read the text content of a local file at the specified absolute path. Read-only."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = READ_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val path = args.string("path")
                if (path.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: path is required.",
                        error = "path is required",
                    )
                }
                return read(path.trim()).fold(
                    onSuccess = { content ->
                        ToolResult(
                            success = true,
                            observationText = content.ifEmpty { "(empty file)" },
                            structuredData = mapOf("path" to path, "length" to content.length),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not read file at \"$path\".",
                            error = error.message ?: "File read failed",
                        )
                    },
                )
            }
        }

    fun writeFile(write: suspend (String, String, Boolean) -> Result<Unit>): Tool =
        object : Tool {
            override val name = WRITE_FILE
            override val description =
                "Create or write text content to a local file at the given path. Specify append=true to append."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = WRITE_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val path = args.string("path")
                val content = args.string("content")
                val append = args.boolean("append") ?: false
                if (path.isNullOrBlank() || content == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing arguments: path and content are required.",
                        error = "path and content are required",
                    )
                }
                return write(path.trim(), content, append).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Successfully ${if (append) "appended to" else "wrote"} file at \"$path\".",
                            structuredData = mapOf("path" to path, "bytes" to content.toByteArray().size),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not write file at \"$path\".",
                            error = error.message ?: "File write failed",
                        )
                    },
                )
            }
        }

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
    private const val READ_SCHEMA =
        """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path of the file to read."}},"required":["path"]}"""
    private const val WRITE_SCHEMA =
        """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path of the file to write."},"content":{"type":"string","description":"Text content to write."},"append":{"type":"boolean","description":"If true, appends to the file instead of overwriting."}},"required":["path","content"]}"""
}
