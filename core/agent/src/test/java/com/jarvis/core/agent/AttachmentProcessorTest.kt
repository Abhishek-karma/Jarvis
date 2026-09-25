package com.jarvis.core.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttachmentProcessorTest {

    @Test
    fun `formatAttachmentsContext formats document and image attachments`() {
        val attachments = listOf(
            ParsedAttachment.TextDocument(
                uri = "content://docs/1",
                fileName = "notes.txt",
                mimeType = "text/plain",
                textContent = "Meeting notes\n1. Project roadmap",
                lineCount = 2,
            ),
            ParsedAttachment.ImageAttachment(
                uri = "content://images/2",
                fileName = "diagram.png",
                mimeType = "image/png",
            ),
        )

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
        val contextBlock = sb.toString()

        assertTrue(contextBlock.contains("notes.txt"))
        assertTrue(contextBlock.contains("Meeting notes"))
        assertTrue(contextBlock.contains("diagram.png"))
        assertTrue(contextBlock.contains("ATTACHMENTS"))
    }
}
