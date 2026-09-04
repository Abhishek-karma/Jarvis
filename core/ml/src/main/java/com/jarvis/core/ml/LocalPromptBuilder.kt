package com.jarvis.core.ml

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ToolDefinition

/**
 * Turns a normalized [ChatRequest] into the single prompt string the on-device engine accepts.
 *
 * On-device context windows are small (Gemma 4 E2B int-quant fits ~2k-4k tokens), so history is
 * capped at the last [MAX_TURNS] user/assistant pairs. When agent tool-calling is active
 * (request.toolsAvailable non-empty) the cap tightens to [MAX_TURNS_WITH_TOOLS] because tool
 * results are verbose, tool definitions are injected up front, and TOOL / tool-call ASSISTANT
 * turns are rendered in the same structured <tool_call> protocol the model itself emits — so the
 * engine sees a self-consistent history across loop iterations.
 *
 * Pure function — unit-tested without any Android dependency.
 */
object LocalPromptBuilder {
    const val MAX_TURNS = 8
    const val MAX_TURNS_WITH_TOOLS = 6

    fun build(request: ChatRequest): String {
        val tools = request.toolsAvailable.orEmpty()
        val maxTurns = if (tools.isEmpty()) MAX_TURNS else MAX_TURNS_WITH_TOOLS

        val lines = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("System: $it") }
            if (tools.isNotEmpty()) add(toolInstructions(tools))

            // Keep at most maxTurns exchange-pairs of the tail + the current prompt.
            val tail = request.conversationHistory.takeLast(maxTurns * 2)
            for (message in tail) {
                val line = when (message.role) {
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

    /**
     * An assistant turn that ended in a tool call is re-rendered as the structured
     * <tool_call> line the model produces, keeping the in-context history coherent without
     * any provider-dialect fields (toolCallId etc.) leaking into the prompt.
     */
    private fun assistantLine(message: Message): String? {
        if (message.toolCallName != null && !message.toolCallArgsJson.isNullOrBlank()) {
            return "Assistant: <tool_call>{\"name\":\"${message.toolCallName}\",\"args\":${message.toolCallArgsJson}}</tool_call>"
        }
        return message.content.takeIf { it.isNotBlank() }?.let { "Assistant: $it" }
    }

    /**
     * Declares the agent's tools to the on-device model in the compact, token-lean protocol
     * it understands, and tells it how to request one. Only names + descriptions are listed —
     * the full JSON-Schema would eat the small context window for little gain.
     */
    private fun toolInstructions(tools: List<ToolDefinition>): String = buildString {
        appendLine("System: You are an agent that can call tools. Available tools:")
        for (tool in tools) {
            appendLine("- ${tool.name}: ${tool.description}")
        }
        appendLine("To use a tool, respond with exactly one line of the form:")
        appendLine("<tool_call>{\"name\":\"tool_name\",\"args\":{\"argument\":\"value\"}}</tool_call>")
        appendLine("Then wait for the Tool Result before continuing, and finally answer the user.")
    }
}
