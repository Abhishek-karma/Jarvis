package com.jarvis.core.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuditRedactionTest {
    @Test
    fun `values under sensitive keys become length plus hash markers`() {
        val redacted = AuditRedaction.redact("""{"to": "a@b.c", "body": "hello world"}""")

        assertFalse(redacted.contains("hello world"))
        assertFalse(redacted.contains("a@b.c"))
        assertTrue(redacted.contains("[redacted len=11 sha256="))
        assertTrue(redacted.contains("[redacted len=5 sha256="))
    }

    @Test
    fun `nested arrays are walked so message bodies are caught`() {
        val redacted = AuditRedaction.redact("""{"messages": [{"role": "user", "content": "top secret"}]}""")

        assertFalse(redacted.contains("top secret"))
        assertTrue(redacted.contains("[redacted len=10 sha256="))
        assertTrue(redacted.contains("user"))
    }

    @Test
    fun `args with no sensitive keys pass through unchanged`() {
        val redacted = AuditRedaction.redact("""{"level": 1}""")

        assertTrue(redacted.contains("\"level\":1"))
        assertFalse(redacted.contains("redacted"))
    }

    @Test
    fun `unparseable args are redacted wholesale (fail closed)`() {
        val raw = "not json"
        assertEquals(
            "{\"error\":\"[redaction failed — unparseable args]\"}",
            AuditRedaction.redact(raw),
        )
    }

    @Test
    fun `concurrent redaction produces correct stable markers`() {


        val values = (0 until 500).map { "payload-$it" + "x".repeat(it % 37) }
        val expected =
            values.map { v ->
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(v.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                "[redacted len=${v.length} sha256=${hash.take(12)}]"
            }

        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures =
                values.map { v ->
                    pool.submit(Callable { AuditRedaction.redact("""{"content": "$v"}""") })
                }
            futures.forEachIndexed { i, f ->

                val redacted = f.get(60, TimeUnit.SECONDS)
                assertTrue(redacted.contains(expected[i]), "marker mismatch at $i: $redacted")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
