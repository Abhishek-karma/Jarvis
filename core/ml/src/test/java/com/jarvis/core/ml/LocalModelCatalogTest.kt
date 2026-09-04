package com.jarvis.core.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalModelCatalogTest {
    private val manifest =
        """
        {
          "version": 1,
          "models": [
            {
              "id": "gemma-2-2b-it",
              "displayName": "Gemma 2 2B",
              "family": "Gemma 2",
              "runtime": "LITERT_LM",
              "fileName": "gemma-2-2b-it-gpu-int4.litertlm",
              "url": "https://example.com/gemma.litertlm",
              "manualPage": "https://example.com",
              "checksumSha256": "abc123",
              "approxSizeLabel": "~1.6 GB",
              "ramNote": "Fits 8 GB devices",
              "license": "Gemma Terms of Use"
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `parse reads a manifest entry`() {
        val catalog = LocalModelCatalog(source = { null })
        val models = catalog.parse(manifest)

        assertEquals(1, models.size)
        val spec = models.first()
        assertEquals("gemma-2-2b-it", spec.id)
        assertEquals("Gemma 2 2B", spec.displayName)
        assertEquals(LocalRuntime.LITERT_LM, spec.runtime)
        assertEquals("gemma-2-2b-it-gpu-int4.litertlm", spec.fileName)
        assertEquals("https://example.com/gemma.litertlm", spec.url)
        assertEquals("abc123", spec.checksumSha256)
    }

    @Test
    fun `parse tolerates a missing or corrupt manifest`() {
        val catalog = LocalModelCatalog(source = { null })
        assertTrue(catalog.parse("not json {").isEmpty())
        assertTrue(catalog.parse("").isEmpty())
    }

    @Test
    fun `load returns empty when the source is absent`() {
        val catalog = LocalModelCatalog(source = { null })
        assertTrue(catalog.load().isEmpty())
    }
}
