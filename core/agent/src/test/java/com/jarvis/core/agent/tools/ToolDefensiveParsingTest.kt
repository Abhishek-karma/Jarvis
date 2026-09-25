package com.jarvis.core.agent.tools

import com.jarvis.core.agent.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Defensive parsing tests: every tool must return ToolResult(success=false)
 * on bad input, never throw an unhandled exception.
 */
class ToolDefensiveParsingTest {

    private val garbageInputs = listOf(
        "",
        "null",
        "[]",
        "123",
        "{invalid",
        """{"key": undefined}""",
    )

    @Test
    fun `calculator with empty JSON returns failure`() = runTest {
        val tool = CalculatorTool.create()
        val result = tool.execute("{}")
        assertFalse(result.success)
    }

    @Test
    fun `calculator with non-JSON input returns failure`() = runTest {
        val tool = CalculatorTool.create()
        val result = tool.execute("not json at all")
        assertFalse(result.success)
    }

    @Test
    fun `calculator with null expression returns failure`() = runTest {
        val tool = CalculatorTool.create()
        val result = tool.execute("""{"expression": null}""")
        assertFalse(result.success)
    }

    @Test
    fun `calculator with garbage inputs never throws`() = runTest {
        val tool = CalculatorTool.create()
        for (input in garbageInputs) {
            val result = runCatching { tool.execute(input) }
            assertTrue(result.isSuccess, "CalculatorTool threw on input: $input")
            assertFalse(result.getOrNull()!!.success)
        }
    }

    @Test
    fun `calculator with malformed expression returns failure not exception`() = runTest {
        val tool = CalculatorTool.create()
        val result = tool.execute("""{"expression": "2 +* 3"}""")
        assertFalse(result.success)
    }

    @Test
    fun `calculator with empty string expression returns failure`() = runTest {
        val tool = CalculatorTool.create()
        val result = tool.execute("""{"expression": ""}""")
        assertFalse(result.success)
    }

    @Test
    fun `createFile with empty JSON returns failure`() = runTest {
        val tool = FilesTools.createFile { _, _, _ -> Result.success("/path") }
        val result = tool.execute("{}")
        assertFalse(result.success)
        assertTrue(result.error?.contains("file_name") == true)
    }

    @Test
    fun `createFile with blank file_name returns failure`() = runTest {
        val tool = FilesTools.createFile { _, _, _ -> Result.success("/path") }
        val result = tool.execute("""{"file_name": "   "}""")
        assertFalse(result.success)
    }

    @Test
    fun `createFile with missing content defaults to empty string`() = runTest {
        var capturedContent = "NOT_SET"
        val tool = FilesTools.createFile { _, content, _ ->
            capturedContent = content
            Result.success("/ok")
        }
        val result = tool.execute("""{"file_name": "test.txt"}""")
        assertTrue(result.success)
        assertTrue(capturedContent.isEmpty())
    }

    @Test
    fun `createFile with garbage inputs never throws`() = runTest {
        val tool = FilesTools.createFile { _, _, _ -> Result.success("/ok") }
        for (input in garbageInputs) {
            val result = runCatching { tool.execute(input) }
            assertTrue(result.isSuccess, "createFile threw on input: $input")
            assertFalse(result.getOrNull()!!.success)
        }
    }

    @Test
    fun `createFile with create failure propagates error result`() = runTest {
        val tool = FilesTools.createFile { _, _, _ -> Result.failure(IllegalStateException("disk full")) }
        val result = tool.execute("""{"file_name": "test.txt"}""")
        assertFalse(result.success)
        assertTrue(result.observationText.contains("disk full"))
    }

    @Test
    fun `searchFiles with garbage inputs never throws`() = runTest {
        val tool = FilesTools.searchFiles { Result.success(emptyList()) }
        for (input in garbageInputs) {
            val result = runCatching { tool.execute(input) }
            assertTrue(result.isSuccess, "searchFiles threw on input: $input")
        }
    }

    @Test
    fun `readFile with empty JSON returns failure`() = runTest {
        val tool = FilesTools.readFile { Result.success("content") }
        val result = tool.execute("{}")
        assertFalse(result.success)
    }

    @Test
    fun `readFile with garbage inputs never throws`() = runTest {
        val tool = FilesTools.readFile { Result.success("content") }
        for (input in garbageInputs) {
            val result = runCatching { tool.execute(input) }
            assertTrue(result.isSuccess, "readFile threw on input: $input")
            assertFalse(result.getOrNull()!!.success)
        }
    }
}
