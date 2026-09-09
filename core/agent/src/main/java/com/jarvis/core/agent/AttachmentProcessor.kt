package com.jarvis.core.agent

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

sealed interface ParsedAttachment {
    val uri: String
    val fileName: String
    val mimeType: String

    data class TextDocument(
        override val uri: String,
        override val fileName: String,
        override val mimeType: String,
        val textContent: String,
        val lineCount: Int,
        val truncated: Boolean = false,
    ) : ParsedAttachment

    data class ImageAttachment(
        override val uri: String,
        override val fileName: String,
        override val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
    ) : ParsedAttachment

    data class Unsupported(
        override val uri: String,
        override val fileName: String,
        override val mimeType: String,
        val reason: String,
    ) : ParsedAttachment
}

/**
 * Processor for multi-modal attachments (documents, images, text files) shared with Jarvis.
 */
class AttachmentProcessor(
    private val context: Context,
) {
    companion object {
        private const val MAX_DOCUMENT_CHARS = 30_000
    }

    suspend fun parseAttachment(uri: Uri, mimeType: String?, fileName: String = "attachment"): ParsedAttachment =
        withContext(Dispatchers.IO) {
            val resolvedMime = mimeType ?: context.contentResolver.getType(uri) ?: "application/octet-stream"

            when {
                resolvedMime.startsWith("text/") || resolvedMime == "application/json" || resolvedMime == "application/xml" -> {
                    parseTextFile(uri, resolvedMime, fileName)
                }
                resolvedMime.startsWith("image/") -> {
                    ParsedAttachment.ImageAttachment(
                        uri = uri.toString(),
                        fileName = fileName,
                        mimeType = resolvedMime,
                    )
                }
                resolvedMime == "application/pdf" -> {
                    // Read text lines or stream header representation
                    parseTextFile(uri, resolvedMime, fileName)
                }
                else -> {
                    ParsedAttachment.Unsupported(
                        uri = uri.toString(),
                        fileName = fileName,
                        mimeType = resolvedMime,
                        reason = "MIME type $resolvedMime is not directly parseable as plain text.",
                    )
                }
            }
        }

    private fun parseTextFile(uri: Uri, mimeType: String, fileName: String): ParsedAttachment {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ParsedAttachment.Unsupported(uri.toString(), fileName, mimeType, "Could not open stream.")

            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val sb = StringBuilder()
                var lineCount = 0
                var truncated = false

                var line = reader.readLine()
                while (line != null) {
                    lineCount++
                    if (sb.length + line.length + 1 > MAX_DOCUMENT_CHARS) {
                        truncated = true
                        sb.append("\n... [document truncated to fit context budget]")
                        break
                    }
                    sb.append(line).append("\n")
                    line = reader.readLine()
                }

                ParsedAttachment.TextDocument(
                    uri = uri.toString(),
                    fileName = fileName,
                    mimeType = mimeType,
                    textContent = sb.toString(),
                    lineCount = lineCount,
                    truncated = truncated,
                )
            }
        } catch (e: Exception) {
            ParsedAttachment.Unsupported(
                uri = uri.toString(),
                fileName = fileName,
                mimeType = mimeType,
                reason = "Error reading content: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /**
     * Formats parsed attachments into an enriched prompt block for LLM consumption.
     */
    fun formatAttachmentsContext(attachments: List<ParsedAttachment>): String {
        if (attachments.isEmpty()) return ""

        val sb = StringBuilder("\n--- ATTACHMENTS ---\n")
        attachments.forEachIndexed { index, att ->
            when (att) {
                is ParsedAttachment.TextDocument -> {
                    sb.append("[Attachment ${index + 1}: ${att.fileName} (${att.mimeType}, ${att.lineCount} lines)]\n")
                    sb.append(att.textContent.trim()).append("\n\n")
                }
                is ParsedAttachment.ImageAttachment -> {
                    sb.append("[Attachment ${index + 1}: Image ${att.fileName} (${att.mimeType})]\n\n")
                }
                is ParsedAttachment.Unsupported -> {
                    sb.append("[Attachment ${index + 1}: ${att.fileName} (Unparsed: ${att.reason})]\n\n")
                }
            }
        }
        sb.append("--- END ATTACHMENTS ---\n")
        return sb.toString()
    }
}
