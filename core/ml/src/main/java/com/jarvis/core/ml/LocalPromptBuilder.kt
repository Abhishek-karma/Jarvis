package com.jarvis.core.ml

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ToolDefinition


object LocalPromptBuilder {
    const val MAX_TURNS = 8
    const val MAX_TURNS_WITH_TOOLS = 6

    fun build(request: ChatRequest): String {
        val tools = request.toolsAvailable.orEmpty()
        val maxTurns = if (tools.isEmpty()) MAX_TURNS else MAX_TURNS_WITH_TOOLS

        val lines =
            buildList {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("System: $it") }
                if (tools.isNotEmpty()) add(toolInstructions(tools))


                val tail = request.conversationHistory.takeLast(maxTurns * 2)
                for (message in tail) {
                    val line =
                        when (message.role) {
                            MessageRole.USER -> "User: ${message.content}"
                            MessageRole.ASSISTANT -> assistantLine(message)
                            MessageRole.TOOL -> "Tool Result: ${message.content}"
                            MessageRole.SYSTEM -> "System: ${message.content}"
                        }
                    if (line != null) add(line)
                }
            }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n\n") + "\n\nAssistant:"
    }


    private fun assistantLine(message: Message): String? {
        if (message.toolCallName != null && !message.toolCallArgsJson.isNullOrBlank()) {
            return "Assistant: <tool_call>{\"name\":\"${message.toolCallName}\"," +
                "\"args\":${message.toolCallArgsJson}}</tool_call>"
        }
        return message.content.takeIf { it.isNotBlank() }?.let { "Assistant: $it" }
    }


    private fun toolInstructions(tools: List<ToolDefinition>): String =
        buildString {
            appendLine("System: You are an agent that can call tools. Available tools:")
            for (tool in tools) {
                appendLine("- ${tool.name}: ${tool.description}")
            }
            appendLine("To use a tool, respond with exactly one line of the form:")
            appendLine("<tool_call>{\"name\":\"tool_name\",\"args\":{\"argument\":\"value\"}}</tool_call>")
            appendLine("Then wait for the Tool Result before continuing, and finally answer the user.")
        }
}
