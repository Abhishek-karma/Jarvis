package com.jarvis.core.network

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.network.anthropic.AnthropicProvider
import com.jarvis.core.network.gemini.GeminiProvider
import com.jarvis.core.network.sse.OpenAiCompatibleProvider
import com.jarvis.core.network.LlmProvider
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance


@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ProviderManagerTest {
    private lateinit var providerRepository: ProviderRepository
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var manager: ProviderManager

    private val testDispatchers =
        mockk<DispatcherProvider>().apply {
            every { main } returns kotlinx.coroutines.test.UnconfinedTestDispatcher()
            every { io } returns kotlinx.coroutines.test.UnconfinedTestDispatcher()
            every { default } returns kotlinx.coroutines.test.UnconfinedTestDispatcher()
        }

    @BeforeEach
    fun setUp() {
        providerRepository = mockk(relaxed = true)
        apiKeyStore = mockk(relaxed = true)
        coEvery { providerRepository.observeProviders() } returns flowOf(emptyList())
        coEvery { apiKeyStore.getKey(any()) } returns null

        manager =
            ProviderManager(
                providerRepository = providerRepository,
                apiKeyStore = apiKeyStore,
                okHttpClient = OkHttpClient(),
                moshi = Moshi.Builder().build(),
                dispatchers = testDispatchers,
            )
    }

    private fun config(
        id: String,
        type: ProviderType,
    ) = ProviderConfig(
        id = id,
        name = "Test $type",
        baseUrl = "https://example.test",
        type = type,
    )

    @Test
    fun `adapterFor returns an OpenAiCompatibleProvider for OPENAI_COMPATIBLE`() {
        val adapter = manager.adapterFor(config("p1", ProviderType.OPENAI_COMPATIBLE))

        assertTrue(adapter is OpenAiCompatibleProvider)
        assertEquals("p1", adapter.id)
    }

    @Test
    fun `adapterFor returns an AnthropicProvider for ANTHROPIC`() {
        val adapter = manager.adapterFor(config("p1", ProviderType.ANTHROPIC))

        assertTrue(adapter is AnthropicProvider)
        assertEquals("p1", adapter.id)
    }

    @Test
    fun `adapterFor returns a GeminiProvider for GEMINI`() {
        val adapter = manager.adapterFor(config("p1", ProviderType.GEMINI))

        assertTrue(adapter is GeminiProvider)
        assertEquals("p1", adapter.id)
    }

    @Test
    fun `adapterFor caches per id`() {
        val first = manager.adapterFor(config("p1", ProviderType.OPENAI_COMPATIBLE))
        val second = manager.adapterFor(config("p1", ProviderType.OPENAI_COMPATIBLE))

        assertTrue(first === second)
    }

    @Test
    fun `re-typing a provider rebuilds the right adapter`() {
        val openAi = manager.adapterFor(config("p1", ProviderType.OPENAI_COMPATIBLE))
        val anthropic = manager.adapterFor(config("p1", ProviderType.ANTHROPIC))

        assertTrue(openAi is OpenAiCompatibleProvider)
        assertTrue(anthropic is AnthropicProvider)


        assertFalse(anthropic === openAi)
    }

    @Test
    fun `two configs with the same id but different families get separate adapters`() {
        val a = manager.adapterFor(config("shared", ProviderType.ANTHROPIC))
        val b = manager.adapterFor(config("shared", ProviderType.GEMINI))

        assertTrue(a is AnthropicProvider)
        assertTrue(b is GeminiProvider)
        assertFalse(a === b)
    }

    @Test
    fun `dropAdapter evicts every family variant of the id`() {
        val config = config("p1", ProviderType.GEMINI)
        val adapter = manager.adapterFor(config)
        manager.dropAdapter("p1")


        val rebuilt = manager.adapterFor(config)
        assertFalse(rebuilt === adapter)
    }

    @Test
    fun `dropAdapter is a no-op for an unknown id`() {

        manager.dropAdapter("never-created")
    }

    @Test
    fun `H4 - an edited baseUrl verifies against a fresh adapter only after dropAdapter`() {

        val initial = manager.adapterFor(config("p1", ProviderType.OPENAI_COMPATIBLE).copy(baseUrl = "https://old.test"))



        val edited = config("p1", ProviderType.OPENAI_COMPATIBLE).copy(baseUrl = "https://new.test")
        val stale = manager.adapterFor(edited)
        assertTrue(stale === initial)
        assertEquals("https://old.test", stale.baseUrlValue())



        manager.dropAdapter("p1")
        val fresh = manager.adapterFor(edited)
        assertFalse(fresh === initial)
        assertEquals("https://new.test", fresh.baseUrlValue())
    }

    /** baseUrl is private on every adapter; reflect it out for cache-behavior assertions. */
    private fun LlmProvider.baseUrlValue(): String =
        this::class.java
            .getDeclaredField("baseUrl")
            .apply { isAccessible = true }
            .get(this) as String
}
