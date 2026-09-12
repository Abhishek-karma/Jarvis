package com.jarvis.core.ml

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the LiteRT-LM protocol-identity guarantee behind same-conversation tool
 * continuation: replaying Jarvis history as native `initialMessages` produces exactly
 * the turn sequence LiteRT-LM itself would have produced in a live conversation —
 * USER(text) → MODEL(tool call) → TOOL(tool response) → MODEL(final).
 *
 * [LiteRtMessageCodec.toNativeTurn] is the semantic mapping (pure Kotlin, JVM-testable);
 * the SDK adapter in [LiteRtMessageCodec.toNativeMessage] is a 1:1 constructor call per
 * turn kind. The litertlm SDK classes themselves are compiled for Java 21 and cannot be
 * loaded by the project's Java 17 unit-test JVM — real-device verification of the full
 * native path is documented in docs/LOCAL_AGENT_TESTING.md.
 */
class LiteRtMessageCodecTest {

    private fun jarvisMessage(
        role: MessageRole,
        content: String,
        toolCallName: String? = null,
        toolCallArgsJson: String? = null,
    ) = Message(
        id = "m",
        conversationId = "c",
        role = role,
        content = content,
        toolCallName = toolCallName,
        toolCallArgsJson = toolCallArgsJson,
    )

    @Test
    fun `user message maps to native user turn with text`() {
        val turn = LiteRtMessageCodec.toNativeTurn(jarvisMessage(MessageRole.USER, "Create a file"))

        assertEquals(LiteRtMessageCodec.NativeTurn.User("Create a file"), turn)
    }

    @Test
    fun `assistant tool-call message maps to native model turn carrying the recorded call`() {
        val turn =
            LiteRtMessageCodec.toNativeTurn(
                jarvisMessage(
                    MessageRole.ASSISTANT,
                    content = "",
                    toolCallName = "create_file",
                    toolCallArgsJson = """{"file_name":"welcome.txt","content":"welcome"}""",
                ),
            )

        val model = turn as LiteRtMessageCodec.NativeTurn.Model
        assertNull(model.text)
        assertEquals(1, model.toolCalls.size)
        assertEquals("create_file", model.toolCalls[0].name)
        assertEquals("welcome.txt", model.toolCalls[0].arguments["file_name"])
        assertEquals("welcome", model.toolCalls[0].arguments["content"])
    }

    @Test
    fun `assistant tool-call with malformed args json degrades to empty arguments`() {
        val turn =
            LiteRtMessageCodec.toNativeTurn(
                jarvisMessage(
                    MessageRole.ASSISTANT,
                    content = "",
                    toolCallName = "create_file",
                    toolCallArgsJson = "not json",
                ),
            )

        val model = turn as LiteRtMessageCodec.NativeTurn.Model
        assertEquals(1, model.toolCalls.size)
        assertEquals("create_file", model.toolCalls[0].name)
        assertTrue(model.toolCalls[0].arguments.isEmpty())
    }

    @Test
    fun `assistant tool-call args preserve nested structured values`() {
        val turn =
            LiteRtMessageCodec.toNativeTurn(
                jarvisMessage(
                    MessageRole.ASSISTANT,
                    content = "",
                    toolCallName = "create_event",
                    toolCallArgsJson = """{"title":"Demo","count":3,"enabled":true}""",
                ),
            )

        val model = turn as LiteRtMessageCodec.NativeTurn.Model
        assertEquals("Demo", model.toolCalls[0].arguments["title"])
        assertEquals(3, (model.toolCalls[0].arguments["count"] as Number).toInt())
        assertEquals(true, model.toolCalls[0].arguments["enabled"])
    }

    @Test
    fun `plain assistant message maps to native model turn with text`() {
        val turn = LiteRtMessageCodec.toNativeTurn(jarvisMessage(MessageRole.ASSISTANT, "Done."))

        assertEquals(LiteRtMessageCodec.NativeTurn.Model(text = "Done.", toolCalls = emptyList()), turn)
    }

    @Test
    fun `tool result maps to native tool turn with matching tool name`() {
        val turn =
            LiteRtMessageCodec.toNativeTurn(
                jarvisMessage(MessageRole.TOOL, "Successfully created file \"welcome.txt\"", toolCallName = "create_file"),
            )

        assertEquals(
            LiteRtMessageCodec.NativeTurn.Tool(
                toolName = "create_file",
                response = "Successfully created file \"welcome.txt\"",
            ),
            turn,
        )
    }

    @Test
    fun `tool result without a tool name falls back to generic tool name`() {
        val turn = LiteRtMessageCodec.toNativeTurn(jarvisMessage(MessageRole.TOOL, "42%"))

        assertEquals(LiteRtMessageCodec.NativeTurn.Tool(toolName = "tool", response = "42%"), turn)
    }

    @Test
    fun `full agent turn replay is protocol-identical to a live tool-calling conversation`() {
        val history =
            listOf(
                jarvisMessage(MessageRole.USER, "Create a text file named welcome.txt containing welcome."),
                jarvisMessage(
                    MessageRole.ASSISTANT,
                    content = "",
                    toolCallName = "create_file",
                    toolCallArgsJson = """{"file_name":"welcome.txt","content":"welcome"}""",
                ),
                jarvisMessage(MessageRole.TOOL, "Successfully created file \"welcome.txt\" at /ok/welcome.txt", toolCallName = "create_file"),
            )

        val turns = history.map { LiteRtMessageCodec.toNativeTurn(it) }

        assertTrue(turns[0] is LiteRtMessageCodec.NativeTurn.User)
        val modelTurn = turns[1] as LiteRtMessageCodec.NativeTurn.Model
        assertEquals(listOf("create_file"), modelTurn.toolCalls.map { it.name })
        val toolTurn = turns[2] as LiteRtMessageCodec.NativeTurn.Tool
        assertEquals("create_file", toolTurn.toolName)
        // The model turn that follows sees exactly: user request → its own tool call → tool response.
    }
}
