package com.jarvis.core.agent.tools

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathTraversalTest {

    @Test
    fun `createFile rejects path traversal in file_name`() = runTest {
        var wasCreated = false
        val tool = FilesTools.createFile { _, _, _ ->
            wasCreated = true
            Result.success("/path")
        }
        val result = tool.execute("""{"file_name": "../../etc/passwd", "content": "pwned"}""")
        // The tool should reject traversal attempts
        assertFalse(result.success)
        assertTrue(result.error?.contains("path") == true || result.error?.contains("..") == true || result.error?.contains("separator") == true)
    }

    @Test
    fun `createFile rejects forward slash in file_name`() = runTest {
        var wasCreated = false
        val tool = FilesTools.createFile { _, _, _ ->
            wasCreated = true
            Result.success("/path")
        }
        val result = tool.execute("""{"file_name": "subdir/../etc/passwd", "content": "pwned"}""")
        assertFalse(result.success)
    }

    @Test
    fun `createFile rejects backslash in file_name`() = runTest {
        var wasCreated = false
        val tool = FilesTools.createFile { _, _, _ ->
            wasCreated = true
            Result.success("/path")
        }
        val result = tool.execute("""{"file_name": "subdir\\..\\etc\\passwd", "content": "pwned"}""")
        assertFalse(result.success)
    }
}