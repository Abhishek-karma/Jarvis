package com.jarvis.core.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolCallParserTest {
    @Test
    fun `parses a basic tool call`() {
        val calls = ToolCallParser.parseAll("[[{\"name\":\"battery_level\",\"args\":{}}]]")
        assertEquals(1, calls.size)
        assertEquals("battery_level", calls[0].name)
        assertEquals("{}", calls[0].argsJson)
    }

    @Test
    fun `separates surrounding prose from the call`() {
        val text = "I'll check. [[{\"name\":\"battery_level\",\"args\":{\"device\":\"phone\"}}]] done?"
        val calls = ToolCallParser.parseAll(text)
        assertEquals(1, calls.size)
        assertEquals("battery_level", calls[0].name)
        assertTrue(calls[0].argsJson.contains("\"device\":\"phone\""))
        val stripped = ToolCallParser.stripToolCalls(text)
        assertTrue(stripped.contains("I'll check."))
        assertTrue(!stripped.contains("battery_level"))
    }

    @Test
    fun `parses multiple calls in one block`() {
        val text = "[[{\"name\":\"a\",\"args\":{}},{\"name\":\"b\",\"args\":{\"x\":1}}]]"
        val calls = ToolCallParser.parseAll(text)
        assertEquals(2, calls.size)
        assertEquals("a", calls[0].name)
        assertEquals("b", calls[1].name)
    }

    @Test
    fun `tolerates braces inside quoted string values`() {
        val text = "[[{\"name\":\"t\",\"args\":{\"q\":\"a}b]c\"}}]]"
        val calls = ToolCallParser.parseAll(text)
        assertEquals(1, calls.size)
        assertEquals("t", calls[0].name)
        assertTrue(calls[0].argsJson.contains("a}b]c"))
    }

    @Test
    fun `rejects malformed calls instead of partially parsing`() {
        assertTrue(ToolCallParser.parseAll("[[{\"name\":42}]]").isEmpty())
        assertTrue(ToolCallParser.parseAll("[[{\"args\":{}}}]]").isEmpty())
        assertTrue(ToolCallParser.parseAll("[[{\"name\":\"t\",\"args\":oops}]]").isEmpty())
        assertTrue(ToolCallParser.parseAll("[[{\"name\":\"t\"").isEmpty())
        assertTrue(ToolCallParser.parseAll("no markup here").isEmpty())
    }

    @Test
    fun `leaves invalid markup untouched when stripping`() {
        val text = "hello [[oops"
        assertEquals(text, ToolCallParser.stripToolCalls(text))
    }

    @Test
    fun `parses observed on-device gemma toolcall markup`() {
        val text = "<|toolcall|>call:devicecontrol:createfile{filename:\"welcome.txt\",content:\"welcome\"}<tool_call>"
        val calls = ToolCallParser.parseAll(text)
        assertEquals(1, calls.size)
        assertEquals("create_file", calls[0].name)
        assertTrue(calls[0].argsJson.contains("\"filename\":\"welcome.txt\""))
        assertTrue(calls[0].argsJson.contains("\"content\":\"welcome\""))
    }

    @Test
    fun `gemma markup with surrounding prose keeps prose and strips protocol`() {
        val text = "Creating it <|toolcall|>call:devicecontrol:createfile{filename:\"w.txt\",content:\"hi\"}<tool_call> now"
        assertEquals(1, ToolCallParser.parseAll(text).size)
        val stripped = ToolCallParser.stripToolCalls(text)
        assertTrue(!stripped.contains("toolcall", ignoreCase = true))
        assertTrue(!stripped.contains("devicecontrol", ignoreCase = true))
        assertTrue(stripped.contains("Creating it"))
    }

    @Test
    fun `gemma unknown tool names surface raw so the agent layer rejects them`() {
        // Well-formed calls with unresolvable names must not vanish silently: they are
        // returned with the raw name and rejected by the ToolRegistry lookup.
        assertEquals(
            "devicecontrol:delete_everything",
            ToolCallParser.parseAll("<|toolcall|>call:devicecontrol:delete_everything{}<tool_call>").single().name,
        )
        assertEquals(
            "unknown:foo",
            ToolCallParser.parseAll("<|toolcall|>call:unknown:foo{}<tool_call>").single().name,
        )
        // Malformed calls (bad args, unbalanced, no call keyword) stay rejected.
        assertTrue(ToolCallParser.parseAll("<|toolcall|>call:devicecontrol:createfile{filename:oops}<tool_call>").isEmpty())
        assertTrue(ToolCallParser.parseAll("<|toolcall|>call:devicecontrol:createfile{filename:\"x\"").isEmpty())
        assertTrue(ToolCallParser.parseAll("<|toolcall|>hello world<tool_call>").isEmpty())
    }

    @Test
    fun `malformed gemma markers never leak raw protocol`() {
        val stripped = ToolCallParser.stripToolCalls("<|toolcall|>call:devicecontrol:unknown{}<tool_call>")
        assertTrue(!stripped.contains("toolcall", ignoreCase = true))
        assertEquals(
            "devicecontrol:unknown",
            ToolCallParser.parseAll("<|toolcall|>call:devicecontrol:unknown{}<tool_call>").single().name,
        )
    }
}
