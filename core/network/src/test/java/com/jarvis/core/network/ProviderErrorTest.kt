package com.jarvis.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderErrorTest {
    @Test
    fun `classifies 401 as authentication error`() {
        val error = CategorizedProviderError.classify("401", "Unauthorized: bad api key")
        assertEquals(ProviderErrorKind.AUTHENTICATION, error.kind)
        assertFalse(error.isRetryable)
        assertTrue(error.recoveryAction.contains("Settings", ignoreCase = true))
    }

    @Test
    fun `classifies 429 as rate limit error`() {
        val error = CategorizedProviderError.classify("429", "Rate limit exceeded")
        assertEquals(ProviderErrorKind.RATE_LIMIT, error.kind)
        assertTrue(error.isRetryable)
        assertTrue(error.recoveryAction.contains("Retry", ignoreCase = true))
    }

    @Test
    fun `classifies timeout as timeout error`() {
        val error = CategorizedProviderError.classify("504", "Gateway timeout")
        assertEquals(ProviderErrorKind.TIMEOUT, error.kind)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `classifies network error as network kind`() {
        val error = CategorizedProviderError.classify("network", "Unable to resolve host: connection refused")
        assertEquals(ProviderErrorKind.NETWORK, error.kind)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `classifies 503 as service unavailable`() {
        val error = CategorizedProviderError.classify("503", "Service Unavailable")
        assertEquals(ProviderErrorKind.PROVIDER_UNAVAILABLE, error.kind)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `classifies context length exceeded`() {
        val error = CategorizedProviderError.classify("context_length_exceeded", "Maximum context length is 8192 tokens")
        assertEquals(ProviderErrorKind.CONTEXT_TOO_LARGE, error.kind)
        assertFalse(error.isRetryable)
    }
}
