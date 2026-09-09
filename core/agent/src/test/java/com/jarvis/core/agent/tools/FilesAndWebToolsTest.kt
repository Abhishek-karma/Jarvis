package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** JVM tests for the file-search and URL-fetch tools — platform readers are plain lambdas. */
class FilesAndWebToolsTest {
    @Test
    fun `both tools are read-only tier`() {
        assertEquals(PermissionTier.READ_ONLY, FilesTools.searchFiles { Result.success(emptyList()) }.tier)
        assertEquals(PermissionTier.READ_ONLY, WebTools.fetchUrl { Result.success(WebTools.FetchedPage(null, "x")) }.tier)
    }

    @Test
    fun `search_files requires a query`() =
        runBlocking {
            val tool = FilesTools.searchFiles { Result.success(emptyList()) }

            val result = tool.execute("""{}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("query"))
        }

    @Test
    fun `search_files formats matches with sizes`() =
        runBlocking {
            val tool =
                FilesTools.searchFiles { query ->
                    assertEquals("report", query)
                    Result.success(
                        listOf(
                            FilesTools.FileHit("report.pdf", "Download/report.pdf", 2_500_000, 0L),
                        ),
                    )
                }

            val result = tool.execute("""{"query":" report "}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("report.pdf"))
            assertTrue(result.observationText.contains("2.5 MB"))
        }

    @Test
    fun `search_files reports empty results without failure`() =
        runBlocking {
            val tool = FilesTools.searchFiles { Result.success(emptyList()) }

            val result = tool.execute("""{"query":"nothing"}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("No files found"))
        }

    @Test
    fun `search_files failure surfaces the platform error`() =
        runBlocking {
            val tool = FilesTools.searchFiles { Result.failure(IllegalStateException("index locked")) }

            val result = tool.execute("""{"query":"x"}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("index locked"))
        }

    @Test
    fun `fetch_url requires a url`() =
        runBlocking {
            val tool = WebTools.fetchUrl { Result.success(WebTools.FetchedPage(null, "x")) }

            val result = tool.execute("""{}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("url"))
        }

    @Test
    fun `fetch_url caps the observation at MAX_CHARS`() =
        runBlocking {
            val longText = "x".repeat(WebTools.MAX_CHARS + 5_000)
            val tool = WebTools.fetchUrl { Result.success(WebTools.FetchedPage("Page", longText)) }

            val result = tool.execute("""{"url":"https://example.com"}""")

            assertTrue(result.success)
            assertEquals(WebTools.MAX_CHARS, result.observationText.length)
            assertEquals(true, result.structuredData?.get("truncated"))
        }

    @Test
    fun `fetch_url failure surfaces the platform error`() =
        runBlocking {
            val tool = WebTools.fetchUrl { Result.failure(IOException("404")) }

            val result = tool.execute("""{"url":"https://gone.example"}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("404"))
        }

    @Test
    fun `read_file and write_file tiers and execution`() =
        runBlocking {
            assertEquals(PermissionTier.READ_ONLY, FilesTools.readFile { Result.success("abc") }.tier)
            assertEquals(PermissionTier.REVERSIBLE_WRITE, FilesTools.writeFile { _, _, _ -> Result.success(Unit) }.tier)

            val readTool = FilesTools.readFile { path ->
                assertEquals("/data/test.txt", path)
                Result.success("file content")
            }
            val readRes = readTool.execute("""{"path":"/data/test.txt"}""")
            assertTrue(readRes.success)
            assertEquals("file content", readRes.observationText)

            var writtenPath = ""
            var writtenContent = ""
            var writtenAppend = false
            val writeTool = FilesTools.writeFile { path, content, append ->
                writtenPath = path
                writtenContent = content
                writtenAppend = append
                Result.success(Unit)
            }
            val writeRes = writeTool.execute("""{"path":"/data/out.txt","content":"hello world","append":true}""")
            assertTrue(writeRes.success)
            assertEquals("/data/out.txt", writtenPath)
            assertEquals("hello world", writtenContent)
            assertTrue(writtenAppend)
        }
}
