package com.jarvis.core.ml

import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest

object LocalPromptBuilder {
    const val MAX_TURNS = 8

    fun build(request: ChatRequest): String {
        val lines =
            buildList {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("System: $it") }

                val tail = request.conversationHistory.takeLast(MAX_TURNS * 2)
                for (message in tail) {
                    val line =
                        when (message.role) {
                            MessageRole.USER -> "User: ${message.content}"
                            MessageRole.ASSISTANT -> {
                                if (!message.toolCallName.isNullOrBlank()) {
                                    val action = "Assistant [Action: ${message.toolCallName}(${message.toolCallArgsJson.orEmpty()})]"
                                    if (message.content.isNotBlank()) "$action ${message.content}" else action
                                } else {
                                    message.content.takeIf { it.isNotBlank() }?.let { "Assistant: $it" }
                                }
                            }
                            MessageRole.TOOL -> "Tool Result: ${message.content}"
                            MessageRole.SYSTEM -> "System: ${message.content}"
                        }
                    if (line != null) add(line)
                }
            }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n\n") + "\n\nAssistant:"
    }
}
