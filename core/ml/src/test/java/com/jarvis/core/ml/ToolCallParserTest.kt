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

    @Test
    fun `parses gemma 4 syntax with underscore in tool_call markers`() {
        val text = "<|tool_call>call:devicecontrol:createfile{filename:\"welcome.txt\",content:\"welcome\"}<tool_call|>"
        val calls = ToolCallParser.parseAll(text)
        assertEquals(1, calls.size)
        assertEquals("create_file", calls[0].name)
        assertTrue(calls[0].argsJson.contains("\"welcome.txt\""))
        val stripped = ToolCallParser.stripToolCalls(text)
        assertEquals("", stripped.trim())
    }

    @Test
    fun `parses gemma call with single quoted arguments`() {
        val text = "<|tool_call>call:devicecontrol:createfile{filename:'notes.txt',content:'hello world'}<tool_call|>"
        val calls = ToolCallParser.parseAll(text)
        assertEquals(1, calls.size)
        assertEquals("create_file", calls[0].name)
        assertTrue(calls[0].argsJson.contains("\"filename\":\"notes.txt\""))
        assertTrue(calls[0].argsJson.contains("\"content\":\"hello world\""))
    }

    @Test
    fun `parses date time and battery calls`() {
        val timeCalls = ToolCallParser.parseAll("<|tool_call>call:devicecontrol:currenttime{}<tool_call|>")
        assertEquals(1, timeCalls.size)
        assertEquals("get_current_datetime", timeCalls[0].name)

        val batteryCalls = ToolCallParser.parseAll("<|tool_call>call:devicecontrol:battery{}<tool_call|>")
        assertEquals(1, batteryCalls.size)
        assertEquals("battery_level", batteryCalls[0].name)

        val calcCalls = ToolCallParser.parseAll("<|tool_call>call:devicecontrol:calculator{expression:\"24 * 7\"}<tool_call|>")
        assertEquals(1, calcCalls.size)
        assertEquals("calculator", calcCalls[0].name)
    }

    @Test
    fun `parses gemma call without closing marker at end of text`() {
        val text = "<|tool_call>call:devicecontrol:createfile{filename:\"welcome.txt\",content:\"welcome\"}"
        val calls = ToolCallParser.parseAll(text)
        assertEquals(1, calls.size)
        assertEquals("create_file", calls[0].name)
    }

    @Test
    fun `parses and strips real-device observed toolcall syntax variations`() {
        val search = "<|toolcall|>call:google:search{queries:[\"kotlin tutorials\"]}<|tool_call|>"
        val searchCalls = ToolCallParser.parseAll(search)
        assertEquals(1, searchCalls.size)
        assertEquals("search_web", searchCalls[0].name)
        assertTrue(searchCalls[0].argsJson.contains("\"queries\":[\"kotlin tutorials\"]"))
        assertEquals("", ToolCallParser.stripToolCalls(search).trim())

        val camera = "<|toolcall|>call:device:opencamera{}<|tool_call|>"
        val cameraCalls = ToolCallParser.parseAll(camera)
        assertEquals(1, cameraCalls.size)
        assertEquals("launch_app", cameraCalls[0].name)
        assertEquals("{}", cameraCalls[0].argsJson)
        assertEquals("", ToolCallParser.stripToolCalls(camera).trim())

        val create = "<|toolcall|>call:device:createfile{filename:\"additionscript.txt\",content:\"echo 1+1\"}<|tool_call|>"
        val createCalls = ToolCallParser.parseAll(create)
        assertEquals(1, createCalls.size)
        assertEquals("create_file", createCalls[0].name)
        assertTrue(createCalls[0].argsJson.contains("\"filename\":\"additionscript.txt\""))
        assertEquals("", ToolCallParser.stripToolCalls(create).trim())
    }
}
