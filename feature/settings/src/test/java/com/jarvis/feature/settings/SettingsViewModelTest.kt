package com.jarvis.feature.settings

import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.network.sse.OpenAiCompatibleProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SettingsViewModel
    private lateinit var providerRepository: ProviderRepository
    private lateinit var providerManager: ProviderManager
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var providersFlow: MutableStateFlow<List<ProviderConfig>>

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        providerRepository = mockk(relaxed = true)
        providerManager = mockk(relaxed = true)
        apiKeyStore = mockk(relaxed = true)
        providersFlow = MutableStateFlow(emptyList())

        coEvery { providerRepository.observeProviders() } returns providersFlow

        viewModel = SettingsViewModel(
            providerRepository = providerRepository,
            providerManager = providerManager,
            apiKeyStore = apiKeyStore,
            dispatchers = com.jarvis.core.common.DispatcherProvider(),
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── List actions ──────────────────────────────────────────────────────────

    @Test
    fun `deleteProvider removes from repository and key store`() = runTest {
        coEvery { providerRepository.delete(any()) } just Runs
        coEvery { apiKeyStore.removeKey(any()) } just Runs

        viewModel.deleteProvider("p1")
        advanceUntilIdle()

        coVerify { providerRepository.delete("p1") }
        coVerify { apiKeyStore.removeKey("p1") }
    }

    @Test
    fun `setDefault calls repository`() = runTest {
        coEvery { providerRepository.setDefault(any()) } just Runs

        viewModel.setDefault("p1")
        advanceUntilIdle()

        coVerify { providerRepository.setDefault("p1") }
    }

    // ── Edit actions ──────────────────────────────────────────────────────────

    @Test
    fun `resetForNew clears edit state`() {
        viewModel.resetForNew()

        val state = viewModel.editState.value
        assertTrue(state.isNew)
        assertEquals("https://api.openai.com/v1", state.baseUrl)
        assertEquals("", state.name)
        assertEquals("", state.apiKey)
    }

    @Test
    fun `onNameChange updates name and clears errors`() {
        viewModel.onNameChange("OpenAI")

        val state = viewModel.editState.value
        assertEquals("OpenAI", state.name)
        assertNull(state.verificationError)
        assertFalse(state.verificationSuccess)
    }

    @Test
    fun `onBaseUrlChange updates URL and clears errors`() {
        viewModel.onBaseUrlChange("https://api.example.com/v1")

        val state = viewModel.editState.value
        assertEquals("https://api.example.com/v1", state.baseUrl)
    }

    @Test
    fun `onApiKeyChange updates key and clears errors`() {
        viewModel.onApiKeyChange("sk-test-123")

        val state = viewModel.editState.value
        assertEquals("sk-test-123", state.apiKey)
    }

    @Test
    fun `onDefaultChange updates default flag`() {
        viewModel.onDefaultChange(true)

        val state = viewModel.editState.value
        assertTrue(state.isDefault)
    }

    @Test
    fun `verifyAndSave fails when name is empty`() = runTest {
        viewModel.onNameChange("")
        viewModel.onApiKeyChange("sk-test")

        viewModel.verifyAndSave()
        advanceUntilIdle()

        assertEquals("Name is required", viewModel.editState.value.verificationError)
    }

    @Test
    fun `verifyAndSave fails when API key is empty`() = runTest {
        viewModel.onNameChange("Test Provider")
        viewModel.onApiKeyChange("")

        viewModel.verifyAndSave()
        advanceUntilIdle()

        assertEquals("API key is required", viewModel.editState.value.verificationError)
    }

    @Test
    fun `verifyAndSave persists provider on successful verification`() = runTest {
        val mockAdapter = mockk<OpenAiCompatibleProvider>(relaxed = true)
        coEvery { providerManager.adapterFor(any()) } returns mockAdapter
        coEvery { mockAdapter.listModels() } returns Result.success(emptyList())
        coEvery { providerRepository.upsert(any()) } just Runs
        coEvery { providerRepository.setDefault(any()) } just Runs
        coEvery { providerManager.dropAdapter(any()) } just Runs

        viewModel.onNameChange("Test Provider")
        viewModel.onApiKeyChange("sk-test-123")
        viewModel.verifyAndSave()
        advanceUntilIdle()

        coVerify { providerRepository.upsert(match { it.name == "Test Provider" }) }
        assertTrue(viewModel.editState.value.verificationSuccess)
    }

    @Test
    fun `verifyAndSave shows error on failed verification`() = runTest {
        val mockAdapter = mockk<OpenAiCompatibleProvider>(relaxed = true)
        coEvery { providerManager.adapterFor(any()) } returns mockAdapter
        coEvery { mockAdapter.listModels() } returns Result.failure(Exception("401 Unauthorized"))
        coEvery { providerManager.dropAdapter(any()) } just Runs
        coEvery { apiKeyStore.removeKey(any()) } just Runs

        viewModel.onNameChange("Test Provider")
        viewModel.onApiKeyChange("bad-key")
        viewModel.verifyAndSave()
        advanceUntilIdle()

        assertFalse(viewModel.editState.value.verificationSuccess)
        assertTrue(viewModel.editState.value.verificationError?.contains("401") == true)
    }

    @Test
    fun `loadProvider populates edit state from repository`() = runTest {
        val provider = ProviderConfig(id = "p1", name = "OpenAI", baseUrl = "https://api.openai.com/v1")
        coEvery { providerRepository.getProvider("p1") } returns provider
        coEvery { apiKeyStore.getKey("p1") } returns "sk-existing"

        viewModel.loadProvider("p1")
        advanceUntilIdle()

        val state = viewModel.editState.value
        assertEquals("p1", state.providerId)
        assertEquals("OpenAI", state.name)
        assertEquals("sk-existing", state.apiKey)
        assertFalse(state.isNew)
    }

    private fun assertNull(value: Any?) {
        assertEquals(null, value)
    }
}
