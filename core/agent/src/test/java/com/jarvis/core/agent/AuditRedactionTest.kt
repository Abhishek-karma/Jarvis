package com.jarvis.core.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuditRedactionTest {
    @Test
    fun `values under sensitive keys become length plus hash markers`() {
        val redacted = AuditRedaction.redact("""{"to": "a@b.c", "body": "hello world"}""")

        assertFalse(redacted.contains("hello world"))
        assertTrue(redacted.contains("[redacted len=11 sha256="))
        assertTrue(redacted.contains("a@b.c")) // non-sensitive keys pass through
    }

    @Test
    fun `nested arrays are walked so message bodies are caught`() {
        val redacted = AuditRedaction.redact("""{"messages": [{"role": "user", "content": "top secret"}]}""")

        assertFalse(redacted.contains("top secret"))
        assertTrue(redacted.contains("[redacted len=10 sha256="))
        assertTrue(redacted.contains("user")) // role values untouched
    }

    @Test
    fun `args with no sensitive keys pass through unchanged`() {
        val redacted = AuditRedaction.redact("""{"level": 1}""")

        assertTrue(redacted.contains("\"level\":1"))
        assertFalse(redacted.contains("redacted"))
    }

    @Test
    fun `unparseable args pass through untouched`() {
        val raw = "not json"
        assertEquals(raw, AuditRedaction.redact(raw))
    }
}
