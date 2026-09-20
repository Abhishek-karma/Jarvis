package com.jarvis.core.capability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File capabilities - read, write, search, delete files
 */
class FileCapability(
    private val context: Context,
) : Capability {

    override val id = CapabilityIds.FILES_READ

    override val description = "Read, write, search, and manage files on the device"

    override val requiredPermissions = listOf(
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: read, write, search, delete, list",
        )

        return when (action) {
            "read" -> readFile(request)
            "write" -> writeFile(request)
            "search" -> searchFiles(request)
            "delete" -> deleteFile(request)
            "list" -> listFiles(request)
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown file action: $action",
            )
        }
    }

    private suspend fun readFile(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val path = request.parameters["path"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'path' parameter"
        )

        val resolvedFile = resolveFilePath(path)
        if (!resolvedFile.exists()) return@withContext CapabilityResult.Failure("NOT_FOUND", "File does not exist: $path")
        if (resolvedFile.isDirectory) return@withContext CapabilityResult.Failure("IS_DIRECTORY", "Path is a directory: $path")

        val isExternal = !resolvedFile.absolutePath.startsWith(context.filesDir.absolutePath) &&
            !resolvedFile.absolutePath.startsWith(context.cacheDir.absolutePath)

        if (isExternal && !hasStoragePermission()) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Storage permission required",
                missingPermissions = listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            )
        }

        try {
            val content = if (resolvedFile.length() > 500_000) {
                resolvedFile.bufferedReader().use { it.readText().take(500_000) + "\n...(truncated)" }
            } else {
                resolvedFile.readText()
            }
            CapabilityResult.Success(
                output = "File content:\n$content",
                structuredData = mapOf("path" to resolvedFile.absolutePath, "size" to resolvedFile.length()),
            )
        } catch (e: SecurityException) {
            CapabilityResult.Unavailable(
                reason = "Storage permission denied",
                missingPermissions = listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("READ_ERROR", "Failed to read file: ${e.message}")
        }
    }

    private suspend fun writeFile(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val fileName = request.parameters["file_name"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'file_name'"
        )
        val content = request.parameters["content"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'content'"
        )
        val location = (request.parameters["location"] as? String)?.lowercase()?.trim() ?: "downloads"

        try {
            val cleanName = java.io.File(fileName).name
            val mimeType = when {
                cleanName.endsWith(".json", ignoreCase = true) -> "application/json"
                cleanName.endsWith(".csv", ignoreCase = true) -> "text/csv"
                cleanName.endsWith(".html", ignoreCase = true) || cleanName.endsWith(".htm", ignoreCase = true) -> "text/html"
                cleanName.endsWith(".xml", ignoreCase = true) -> "text/xml"
                cleanName.endsWith(".md", ignoreCase = true) -> "text/markdown"
                else -> "text/plain"
            }

            val isDocuments = location == "documents" || location == "document" || location == "doc"
            val relativePath = if (isDocuments) Environment.DIRECTORY_DOCUMENTS else Environment.DIRECTORY_DOWNLOADS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                val collection = if (isDocuments) {
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val uri = context.contentResolver.insert(collection, values)
                    ?: return@withContext CapabilityResult.Failure("INSERT_ERROR", "Failed to insert file into MediaStore")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: return@withContext CapabilityResult.Failure("STREAM_ERROR", "Failed to open output stream")

                CapabilityResult.Success(
                    output = "File created: $relativePath/$cleanName",
                    structuredData = mapOf("path" to "$relativePath/$cleanName", "size" to content.length),
                )
            } else {
                val baseDir = Environment.getExternalStoragePublicDirectory(relativePath)
                baseDir.mkdirs()
                val targetFile = java.io.File(baseDir, cleanName)
                targetFile.writeText(content)
                CapabilityResult.Success(
                    output = "File created: ${targetFile.absolutePath}",
                    structuredData = mapOf("path" to targetFile.absolutePath, "size" to content.length),
                )
            }
        } catch (e: Exception) {
            CapabilityResult.Failure("WRITE_ERROR", "Failed to write file: ${e.message}")
        }
    }

    private suspend fun searchFiles(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val query = request.parameters["query"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'query' parameter"
        )

        if (!hasStoragePermission()) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Storage permission required",
                missingPermissions = listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            )
        }

        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            val results = context.contentResolver
                .query(
                    uri,
                    projection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$query%"),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext() && size < 50) {
                            add(
                                mapOf<String, Any>(
                                    "name" to (cursor.getString(0) ?: "(unnamed)"),
                                    "path" to (cursor.getString(1) ?: ""),
                                    "size" to cursor.getLong(2),
                                    "modified" to cursor.getLong(3) * 1000L,
                                )
                            )
                        }
                    }
                } ?: emptyList()

            CapabilityResult.Success(
                output = if (results.isNotEmpty()) {
                    "Found ${results.size} file(s): ${results.joinToString(", ") { it["name"] as String }}"
                } else {
                    "No files found matching '$query'"
                },
                structuredData = mapOf("results" to results),
            )
        } catch (e: SecurityException) {
            CapabilityResult.Unavailable(
                reason = "Storage permission denied",
                missingPermissions = listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("SEARCH_ERROR", "Failed to search files: ${e.message}")
        }
    }

    private suspend fun deleteFile(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val path = request.parameters["path"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'path' parameter"
        )

        val resolvedFile = resolveFilePath(path)
        if (!resolvedFile.exists()) return@withContext CapabilityResult.Failure("NOT_FOUND", "File does not exist: $path")

        try {
            val deleted = resolvedFile.delete()
            if (deleted) {
                CapabilityResult.Success(
                    output = "File deleted: $path",
                    structuredData = mapOf("path" to path),
                )
            } else {
                CapabilityResult.Failure("DELETE_ERROR", "Failed to delete file: $path")
            }
        } catch (e: Exception) {
            CapabilityResult.Failure("DELETE_ERROR", "Failed to delete file: ${e.message}")
        }
    }

    private suspend fun listFiles(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val path = (request.parameters["path"] as? String) ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val dir = resolveFilePath(path)

        if (!dir.exists() || !dir.isDirectory) {
            return@withContext CapabilityResult.Failure("NOT_FOUND", "Directory does not exist: $path")
        }

        try {
            val files = dir.listFiles()?.map { file ->
                mapOf<String, Any>(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "size" to file.length(),
                    "is_directory" to file.isDirectory,
                    "modified" to file.lastModified(),
                )
            } ?: emptyList()

            CapabilityResult.Success(
                output = "Directory '$path' contains ${files.size} item(s)",
                structuredData = mapOf("files" to files),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("LIST_ERROR", "Failed to list files: ${e.message}")
        }
    }

    private fun resolveFilePath(path: String): java.io.File {
        val trimmed = path.trim()
        val lower = trimmed.lowercase()
        return when {
            lower == "/download" || lower == "download" || lower == "/downloads" || lower == "downloads" -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            lower.startsWith("/download/") || lower.startsWith("download/") -> {
                val sub = trimmed.substringAfter("download/").removePrefix("/")
                java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
            }
            lower.startsWith("/downloads/") || lower.startsWith("downloads/") -> {
                val sub = trimmed.substringAfter("downloads/").removePrefix("/")
                java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
            }
            lower == "/documents" || lower == "documents" -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }
            lower.startsWith("/documents/") || lower.startsWith("documents/") -> {
                val sub = trimmed.substringAfter("documents/").removePrefix("/")
                java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), sub)
            }
            trimmed.startsWith("/") -> java.io.File(trimmed)
            else -> java.io.File(context.filesDir, trimmed)
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}