package com.jarvis.feature.chat.di

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test


class SsrfGuardTest {
    private fun blocked(url: String, because: String) {
        val e =
            assertThrows(IllegalStateException::class.java, { ensurePublicHttpUrlChecked(url) }, "expected block: $url ($because)")
        org.junit.jupiter.api.Assertions.assertTrue(
            e.message?.contains(because) == true,
            "unexpected reason for $url: ${e.message}",
        )
    }

    @Test
    fun `loopback is blocked`() {
        blocked("http://127.0.0.1:8080/x", "private or local")
        blocked("http://[::1]/x", "private or local")
    }

    @Test
    fun `localhost name is blocked`() {
        blocked("http://localhost/api", "Localhost is not allowed")
    }

    @Test
    fun `private and link local ranges are blocked`() {
        blocked("http://10.1.2.3/x", "private or local")
        blocked("http://192.168.0.5/x", "private or local")
        blocked("http://172.16.9.9/x", "private or local")
        blocked("http://169.254.169.254/latest/meta-data/", "private or local")
    }

    @Test
    fun `non http schemes are blocked`() {

        blocked("ftp://example.com/file", "Not a valid URL")
        blocked("file:///etc/passwd", "valid URL")
    }

    @Test
    fun `unparseable url is rejected`() {
        blocked("not a url", "valid URL")
    }

    @Test
    fun `public host passes`() {


        assertDoesNotThrow { ensurePublicHttpUrlChecked("https://8.8.8.8/") }
    }
}
