package com.jarvis.core.ml

import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ToolDefinition

object LocalPromptBuilder {
    const val MAX_TURNS = 8

    /**
     * Builds the single-shot text prompt. Only used for legacy text-prompt engines; the
     * LiteRT-LM path sends native Messages + real tool declarations, so no text tool-call
     * syntax is taught there. The [[...]] block below serves engines without structured tools.
     */
    fun build(request: ChatRequest): String {
        val tools = request.toolsAvailable.orEmpty()
        val lines =
            buildList {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("System: $it") }
                if (tools.isNotEmpty()) add(toolInstructions(tools))

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

    /**
     * Instructs a text-only on-device model how to request tools. The reply is parsed by
     * [ToolCallParser], so the exact `[[{"name":...,"args":{...}}]]` shape matters.
     */
    private fun toolInstructions(tools: List<ToolDefinition>): String =
        buildString {
            appendLine("System: You are an agent that can call tools. Available tools:")
            for (tool in tools) {
                appendLine("- ${tool.name}: ${tool.description}")
            }
            appendLine("To use a tool, respond with exactly one block of the form:")
            appendLine("[[{\"name\":\"tool_name\",\"args\":{\"argument\":\"value\"}}]]")
            appendLine("Then wait for the Tool Result before continuing, and finally answer the user.")
        }.trim()
}
