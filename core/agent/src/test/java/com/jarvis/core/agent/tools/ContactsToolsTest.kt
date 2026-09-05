package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** JVM tests for the contacts tool — the resolver is a plain lambda. */
class ContactsToolsTest {
    @Test
    fun `tier is read-only`() {
        assertEquals(PermissionTier.READ_ONLY, ContactsTools.lookupContact { Result.success(emptyList()) }.tier)
    }

    @Test
    fun `lookup formats phone matches`() =
        runBlocking {
            val tool =
                ContactsTools.lookupContact { name ->
                    assertEquals("Alice", name)
                    Result.success(
                        listOf(
                            ContactsTools.ContactMatch("Alice Chen", phone = "+1 555 0100"),
                            ContactsTools.ContactMatch("Alice Wong", phone = "+1 555 0101", email = "aw@example.com"),
                        ),
                    )
                }

            val result = tool.execute("""{"name":"Alice"}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("2 match(es)"))
            assertTrue(result.observationText.contains("Alice Chen"))
            assertTrue(result.observationText.contains("+1 555 0100"))
            assertTrue(result.observationText.contains("aw@example.com"))
        }

    @Test
    fun `lookup reports no matches`() =
        runBlocking {
            val tool = ContactsTools.lookupContact { Result.success(emptyList()) }

            val result = tool.execute("""{"name":"Zed"}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("No contact"))
        }

    @Test
    fun `lookup truncates beyond five matches`() =
        runBlocking {
            val matches = (1..8).map { ContactsTools.ContactMatch("Contact $it", phone = "$it") }
            val tool = ContactsTools.lookupContact { Result.success(matches) }

            val result = tool.execute("""{"name":"c"}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("truncated"))
            assertEquals(8, result.structuredData?.get("count"))
        }

    @Test
    fun `blank name fails cleanly`() =
        runBlocking {
            val tool = ContactsTools.lookupContact { Result.success(emptyList()) }

            val result = tool.execute("""{"name":"   "}""")

            assertFalse(result.success)
        }

    @Test
    fun `malformed JSON fails cleanly`() =
        runBlocking {
            val tool = ContactsTools.lookupContact { Result.success(emptyList()) }

            val result = tool.execute("""{"name" """)

            assertFalse(result.success)
            assertTrue(result.error!!.contains("JSON"))
        }

    @Test
    fun `resolver failure surfaces the error`() =
        runBlocking {
            val tool = ContactsTools.lookupContact { Result.failure(IllegalStateException("permission denied")) }

            val result = tool.execute("""{"name":"Bob"}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("permission denied"))
        }
}
