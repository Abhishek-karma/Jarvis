package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier


object FilesTools {
    const val SEARCH_FILES = "search_files"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val CREATE_FILE = "create_file"

    val manifestNames: List<String> = listOf(SEARCH_FILES, READ_FILE, CREATE_FILE)

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
        create: (suspend (fileName: String, content: String, location: String?) -> Result<String>)? = null,
    ): List<Tool> = buildList {
        add(searchFiles(search))
        if (read != null) add(readFile(read))
        if (create != null) {
            add(createFile(create))
        } else if (write != null) {
            // Fallback: wire createFile to writeFile if custom create not passed
            add(createFile { fileName, content, _ ->
                write(fileName, content, false).map { fileName }
            })
        }
    }

    fun createFile(create: suspend (fileName: String, content: String, location: String?) -> Result<String>): Tool =
        object : Tool {
            override val name = CREATE_FILE
            override val description =
                "Create a text file with a specified filename and content. " +
                    "Optionally specify location ('downloads', 'documents', 'app_private'). " +
                    "Defaults to 'downloads'. Does not require raw filesystem paths."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = CREATE_FILE_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val fileName = args.string("file_name")
                    ?: args.string("filename")
                    ?: args.string("name")
                    ?: args.string("path")
                val content = args.string("content") ?: ""
                val location = args.string("location")

                if (fileName.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: file_name is required.",
                        error = "file_name is required",
                    )
                }

                // Block path traversal attempts (dot-dot, forward slash, backslash)
                val trimmedName = fileName.trim()
                if (trimmedName.contains("..") || trimmedName.contains("/") || trimmedName.contains("\\")) {
                    return ToolResult(
                        success = false,
                        observationText = "Invalid file name: path separators and '..' are not allowed.",
                        error = "invalid file_name",
                    )
                }

                return create(trimmedName, content, location?.trim()).fold(
                    onSuccess = { savedPath ->
                        ToolResult(
                            success = true,
                            observationText = "Successfully created file \"$fileName\" at $savedPath.",
                            structuredData = mapOf(
                                "file_name" to fileName,
                                "path" to savedPath,
                                "bytes" to content.toByteArray().size,
                            ),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not create file \"$fileName\": ${error.message}",
                            error = error.message ?: "File creation failed",
                        )
                    },
                )
            }
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
                // Block path traversal attempts in read path
                val trimmedPath = path.trim()
                if (trimmedPath.contains("..")) {
                    return ToolResult(
                        success = false,
                        observationText = "Invalid path: '..' is not allowed.",
                        error = "invalid path",
                    )
                }
                return read(trimmedPath).fold(
                    onSuccess = { content ->
                        val truncated = content.length > MAX_CHARS
                        val text = if (truncated) content.take(MAX_CHARS) + "\n...[truncated to $MAX_CHARS chars]" else content.ifEmpty { "(empty file)" }
                        ToolResult(
                            success = true,
                            observationText = text,
                            structuredData = mapOf(
                                "path" to path,
                                "length" to content.length,
                                "truncated" to truncated,
                            ),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not read file at \"$path\": ${error.message}",
                            error = error.message ?: "File read failed",
                        )
                    },
                )
            }
        }

    @Deprecated("Use createFile instead for safe scoped storage file creation")
    fun writeFile(write: suspend (String, String, Boolean) -> Result<Unit>): Tool =
        object : Tool {
            override val name = WRITE_FILE
            override val description =
                "[Deprecated: use create_file instead] Create or write text content to a local file at the given path. Specify append=true to append."
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
    internal const val MAX_CHARS = 6_000

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
    private const val CREATE_FILE_SCHEMA =
        """{"type":"object","properties":{"file_name":{"type":"string","description":"Name of the file to create (e.g. welcome.txt)."},"content":{"type":"string","description":"Text content to write into the file."},"location":{"type":"string","description":"Target directory: 'downloads' (default), 'documents', or 'app_private'."}},"required":["file_name","content"]}"""
}
