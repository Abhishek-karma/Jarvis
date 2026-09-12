package com.jarvis.feature.settings

import com.jarvis.core.common.LocalBenchmarkResult
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.ml.LocalModelBenchmarkRunner
import com.jarvis.core.ml.LocalModelState
import com.jarvis.core.ml.LocalModelStore
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.preferences.ChatMode
import com.jarvis.core.preferences.ThemeMode
import com.jarvis.core.preferences.UserPreferencesRepository
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
import org.junit.jupiter.api.Assertions.assertNotNull
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
    private lateinit var localModelStore: LocalModelStore
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var benchmarkRunner: LocalModelBenchmarkRunner
    private lateinit var providersFlow: MutableStateFlow<List<ProviderConfig>>
    private lateinit var localModelStateFlow: MutableStateFlow<LocalModelState>

    private val testDispatchers =
        mockk<com.jarvis.core.common.DispatcherProvider>().apply {
            every { main } returns testDispatcher
            every { io } returns testDispatcher
            every { default } returns testDispatcher
        }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        providerRepository = mockk(relaxed = true)
        providerManager = mockk(relaxed = true)
        apiKeyStore = mockk(relaxed = true)
        localModelStore = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        benchmarkRunner = mockk(relaxed = true)
        providersFlow = MutableStateFlow(emptyList())
        localModelStateFlow = MutableStateFlow(LocalModelState.NotDownloaded)
        every { localModelStore.status } returns localModelStateFlow

        coEvery { providerRepository.observeProviders() } returns providersFlow

        viewModel =
            SettingsViewModel(
                providerRepository = providerRepository,
                providerManager = providerManager,
                apiKeyStore = apiKeyStore,
                localModelStore = localModelStore,
                userPreferences = userPreferences,
                updateChecker = mockk(relaxed = true),
                benchmarkRunner = benchmarkRunner,
                context = mockk(relaxed = true),
                dispatchers = testDispatchers,
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `preference setters delegate to the repository`() =
        runTest {
            viewModel.setThemeMode(ThemeMode.DARK)
            viewModel.setThinkMode(ThinkMode.ON)
            viewModel.setCautiousMode(true)
            viewModel.setAgentStepCap(25)
            viewModel.setLocalInternetAccess(false)
            viewModel.setLocalTemperature(0.8f)
            viewModel.setLocalTopP(0.95f)
            viewModel.setLocalMaxTokens(2048)
            viewModel.setLocalThreads(6)
            viewModel.setLocalPrewarm(false)
            advanceUntilIdle()

            coVerify { userPreferences.setThemeMode(ThemeMode.DARK) }
            coVerify { userPreferences.setThinkMode(ThinkMode.ON) }
            coVerify { userPreferences.setCautiousModeEnabled(true) }
            coVerify { userPreferences.setAgentStepCap(25) }
            coVerify { userPreferences.setLocalInternetAccess(false) }
            coVerify { userPreferences.setLocalTemperature(0.8f) }
            coVerify { userPreferences.setLocalTopP(0.95f) }
            coVerify { userPreferences.setLocalMaxTokens(2048) }
            coVerify { userPreferences.setLocalThreads(6) }
            coVerify { userPreferences.setLocalPrewarm(false) }
        }

    @Test
    fun `persisted preferences are mirrored into prefsState`() =
        runTest {
            every { userPreferences.themeMode } returns MutableStateFlow(ThemeMode.DARK)
            every { userPreferences.thinkMode } returns MutableStateFlow(ThinkMode.OFF)
            every { userPreferences.cautiousModeEnabled } returns MutableStateFlow(true)
            every { userPreferences.agentStepCap } returns MutableStateFlow(20)
            every { userPreferences.chatMode } returns MutableStateFlow(ChatMode.LOCAL)
            every { userPreferences.localInternetAccess } returns MutableStateFlow(false)
            every { userPreferences.localTemperature } returns MutableStateFlow(0.4f)
            every { userPreferences.localTopP } returns MutableStateFlow(0.85f)
            every { userPreferences.localMaxTokens } returns MutableStateFlow(512)
            every { userPreferences.localThreads } returns MutableStateFlow(8)
            every { userPreferences.localPrewarm } returns MutableStateFlow(false)
            val bench =
                LocalBenchmarkResult(
                    modelId = "gemma-2b",
                    modelName = "Gemma 2B",
                    promptTokens = 24,
                    completionTokens = 96,
                    timeToFirstTokenMs = 120L,
                    generationSpeedTps = 18.5f,
                    totalTimeMs = 5200L,
                    peakMemoryMb = 240L,
                    threadCount = 4,
                )
            every { userPreferences.localBenchmarkResult } returns MutableStateFlow(bench)

            viewModel =
                SettingsViewModel(
                    providerRepository = providerRepository,
                    providerManager = providerManager,
                    apiKeyStore = apiKeyStore,
                    localModelStore = localModelStore,
                    userPreferences = userPreferences,
                    updateChecker = mockk(relaxed = true),
                    benchmarkRunner = benchmarkRunner,
                    context = mockk(relaxed = true),
                    dispatchers = testDispatchers,
                )
            advanceUntilIdle()

            val prefs = viewModel.prefsState.value
            assertEquals(ThemeMode.DARK, prefs.themeMode)
            assertEquals(ThinkMode.OFF, prefs.thinkMode)
            assertTrue(prefs.cautiousModeEnabled)
            assertEquals(20, prefs.agentStepCap)
            assertEquals(ChatMode.LOCAL, prefs.chatMode)
            assertFalse(prefs.localInternetAccess)
            assertEquals(0.4f, prefs.localTemperature)
            assertEquals(0.85f, prefs.localTopP)
            assertEquals(512, prefs.localMaxTokens)
            assertEquals(8, prefs.localThreads)
            assertFalse(prefs.localPrewarm)
            assertEquals(bench, prefs.localBenchmarkResult)
        }

    @Test
    fun `runLocalBenchmark triggers benchmarkRunner and updates repository on success`() =
        runTest {
            val expectedBench =
                LocalBenchmarkResult(
                    modelId = "gemma-2b",
                    modelName = "Gemma 2B",
                    promptTokens = 24,
                    completionTokens = 100,
                    timeToFirstTokenMs = 90L,
                    generationSpeedTps = 22.4f,
                    totalTimeMs = 4500L,
                    peakMemoryMb = 310L,
                    threadCount = 4,
                )
            coEvery { benchmarkRunner.runBenchmark(any()) } returns Result.success(expectedBench)
            coEvery { userPreferences.setLocalBenchmarkResult(any()) } just Runs

            viewModel.runLocalBenchmark()
            advanceUntilIdle()

            coVerify { benchmarkRunner.runBenchmark(any()) }
            coVerify { userPreferences.setLocalBenchmarkResult(expectedBench) }
            assertEquals(expectedBench, viewModel.prefsState.value.localBenchmarkResult)
            assertFalse(viewModel.prefsState.value.isBenchmarking)
        }

    @Test
    fun `deleteProvider removes from repository and key store`() =
        runTest {
            coEvery { providerRepository.delete(any()) } just Runs
            coEvery { apiKeyStore.removeKey(any()) } just Runs

            viewModel.deleteProvider("p1")
            advanceUntilIdle()

            coVerify { providerRepository.delete("p1") }
            coVerify { apiKeyStore.removeKey("p1") }
        }

    @Test
    fun `setDefault calls repository`() =
        runTest {
            coEvery { providerRepository.setDefault(any()) } just Runs

            viewModel.setDefault("p1")
            advanceUntilIdle()

            coVerify { providerRepository.setDefault("p1") }
        }

    @Test
    fun `resetForNew clears edit state`() {
        viewModel.resetForNew()

        val state = viewModel.editState.value
        assertTrue(state.isNew)
        assertEquals("https://api.openai.com", state.baseUrl)
        assertEquals("", state.name)
        assertEquals("", state.model)
        assertEquals("", state.apiKey)
        assertEquals(ProviderType.OPENAI_COMPATIBLE, state.type)
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
    fun `onModelChange updates model and clears errors`() {
        viewModel.onModelChange("llama3.2")

        val state = viewModel.editState.value
        assertEquals("llama3.2", state.model)
        assertNull(state.verificationError)
        assertFalse(state.verificationSuccess)
    }

    @Test
    fun `onDefaultChange updates default flag`() {
        viewModel.onDefaultChange(true)

        val state = viewModel.editState.value
        assertTrue(state.isDefault)
    }

    @Test
    fun `verifyAndSave fails when name is empty`() =
        runTest {
            viewModel.onNameChange("")
            viewModel.onApiKeyChange("sk-test")

            viewModel.verifyAndSave()
            advanceUntilIdle()

            assertEquals("Name is required", viewModel.editState.value.verificationError)
        }

    @Test
    fun `verifyAndSave allows empty API key for keyless HTTPS gateways`() =
        runTest {
            val mockAdapter = mockk<LlmProvider>(relaxed = true)
            coEvery { providerManager.adapterFor(any()) } returns mockAdapter
            coEvery { mockAdapter.listModels() } returns Result.success(emptyList())
            coEvery { providerRepository.upsert(any()) } just Runs
            coEvery { providerManager.dropAdapter(any()) } just Runs
            coEvery { apiKeyStore.removeKey(any()) } just Runs

            viewModel.onNameChange("Gateway")
            viewModel.onBaseUrlChange("https://llm.example.com")
            viewModel.onApiKeyChange("")
            viewModel.verifyAndSave()
            advanceUntilIdle()

            coVerify { providerRepository.upsert(match { it.name == "Gateway" }) }
            coVerify { apiKeyStore.removeKey(any()) }
            assertTrue(viewModel.editState.value.verificationSuccess)
            assertEquals(null, viewModel.editState.value.verificationError)
        }

    @Test
    fun `verifyAndSave persists provider on successful verification`() =
        runTest {
            val mockAdapter = mockk<LlmProvider>(relaxed = true)
            coEvery { providerManager.adapterFor(any()) } returns mockAdapter
            coEvery { mockAdapter.listModels() } returns Result.success(emptyList())
            coEvery { providerRepository.upsert(any()) } just Runs
            coEvery { providerRepository.setDefault(any()) } just Runs
            coEvery { providerManager.dropAdapter(any()) } just Runs
            coEvery { apiKeyStore.putKey(any(), any()) } just Runs

            viewModel.onNameChange("Test Provider")

            viewModel.onBaseUrlChange("https://api.openai.com/v1")
            viewModel.onModelChange("gpt-4o-mini")
            viewModel.onApiKeyChange("sk-test-123")
            viewModel.verifyAndSave()
            advanceUntilIdle()

            coVerify {
                providerRepository.upsert(
                    match {
                        it.name == "Test Provider" &&
                            it.baseUrl == "https://api.openai.com" &&
                            it.model == "gpt-4o-mini" &&
                            it.type == ProviderType.OPENAI_COMPATIBLE
                    },
                )
            }
            assertTrue(viewModel.editState.value.verificationSuccess)
        }

    @Test
    fun `verifyAndSave persists provider type for non-default families`() =
        runTest {
            val mockAdapter = mockk<LlmProvider>(relaxed = true)
            coEvery { providerManager.adapterFor(any()) } returns mockAdapter
            coEvery { mockAdapter.listModels() } returns Result.success(emptyList())
            coEvery { providerRepository.upsert(any()) } just Runs
            coEvery { providerManager.dropAdapter(any()) } just Runs
            coEvery { apiKeyStore.putKey(any(), any()) } just Runs

            viewModel.onNameChange("Anthropic")
            viewModel.onTypeChange(ProviderType.ANTHROPIC)
            viewModel.onApiKeyChange("sk-ant-test")
            viewModel.verifyAndSave()
            advanceUntilIdle()

            coVerify {
                providerRepository.upsert(
                    match {
                        it.type == ProviderType.ANTHROPIC &&
                            it.baseUrl == "https://api.anthropic.com"
                    },
                )
            }
            assertTrue(viewModel.editState.value.verificationSuccess)
        }

    @Test
    fun `verifyAndSave shows error on failed verification`() =
        runTest {
            val mockAdapter = mockk<LlmProvider>(relaxed = true)
            coEvery { providerManager.adapterFor(any()) } returns mockAdapter
            coEvery { mockAdapter.listModels() } returns Result.failure(Exception("401 Unauthorized"))
            coEvery { providerManager.dropAdapter(any()) } just Runs
            coEvery { apiKeyStore.removeKey(any()) } just Runs

            viewModel.onNameChange("Test Provider")
            viewModel.onApiKeyChange("bad-key")
            viewModel.verifyAndSave()
            advanceUntilIdle()

            assertFalse(viewModel.editState.value.verificationSuccess)
            assertTrue(
                viewModel.editState.value.verificationError
                    ?.contains("401") == true,
            )
        }

    @Test
    fun `verifyAndSave with a malformed URL fails instead of crashing`() =
        runTest {



            val mockAdapter = mockk<LlmProvider>(relaxed = true)
            coEvery { providerManager.adapterFor(any()) } returns mockAdapter
            coEvery { mockAdapter.listModels() } throws IllegalArgumentException(
                "Expected URL scheme 'http' or 'https' but was 'hhttp'",
            )
            coEvery { providerManager.dropAdapter(any()) } just Runs
            coEvery { apiKeyStore.removeKey(any()) } just Runs

            viewModel.onNameChange("Typo Provider")
            viewModel.onBaseUrlChange("hhttp://api.example.com")
            viewModel.verifyAndSave()
            advanceUntilIdle()

            assertFalse(viewModel.editState.value.verificationSuccess)
            assertTrue(
                (viewModel.editState.value.verificationError ?: "").contains("scheme"),
            )
            coVerify(exactly = 0) { providerRepository.upsert(any()) }
        }

    @Test
    fun `failed verify of an existing provider restores its stored API key`() =
        runTest {



            coEvery { apiKeyStore.getKey("p1") } returns "sk-kept"
            coEvery { providerRepository.getProvider("p1") } returns
                ProviderConfig(id = "p1", name = "P1", baseUrl = "https://old.test", model = "m1")
            viewModel.loadProvider("p1")
            advanceUntilIdle()

            val mockAdapter = mockk<LlmProvider>(relaxed = true)
            coEvery { providerManager.adapterFor(any()) } returns mockAdapter
            coEvery { mockAdapter.listModels() } returns Result.failure(Exception("connection refused"))
            coEvery { providerRepository.upsert(any()) } just Runs
            coEvery { providerManager.dropAdapter(any()) } just Runs
            coEvery { apiKeyStore.removeKey(any()) } just Runs
            coEvery { apiKeyStore.putKey(any(), any()) } just Runs

            viewModel.onApiKeyChange("")
            viewModel.onBaseUrlChange("https://dead.example.com")
            viewModel.verifyAndSave()
            advanceUntilIdle()

            assertFalse(viewModel.editState.value.verificationSuccess)
            coVerify { apiKeyStore.putKey("p1", "sk-kept") }
        }

    @Test
    fun `verifyAndSave drops the cached adapter before building the verify adapter`() =
        runTest {




            val existingId = "existing-p1"
            coEvery { providerRepository.getProvider(existingId) } returns
                ProviderConfig(
                    id = existingId,
                    name = "Old Name",
                    baseUrl = "https://old.test",
                    model = "m1",
                )
            coEvery { apiKeyStore.getKey(existingId) } returns "sk-old"
            viewModel.loadProvider(existingId)
            advanceUntilIdle()

            val mockAdapter = mockk<LlmProvider>(relaxed = true)
            coEvery { providerManager.adapterFor(any()) } returns mockAdapter
            coEvery { mockAdapter.listModels() } returns Result.success(emptyList())
            coEvery { providerRepository.upsert(any()) } just Runs
            coEvery { providerManager.dropAdapter(any()) } just Runs
            coEvery { apiKeyStore.removeKey(any()) } just Runs
            coEvery { apiKeyStore.putKey(any(), any()) } just Runs

            viewModel.onNameChange("Renamed")
            viewModel.onBaseUrlChange("https://new.example.com")
            viewModel.verifyAndSave()
            advanceUntilIdle()



            coVerify { providerManager.dropAdapter(existingId) }
            coVerify { providerManager.adapterFor(match { it.id == existingId && it.baseUrl == "https://new.example.com" }) }
            assertTrue(viewModel.editState.value.verificationSuccess)
        }

    @Test
    fun `loadProvider populates edit state from repository`() =
        runTest {
            val provider =
                ProviderConfig(
                    id = "p1",
                    name = "OpenAI",
                    baseUrl = "https://api.openai.com",
                    model = "gpt-4o-mini",
                )
            coEvery { providerRepository.getProvider("p1") } returns provider
            coEvery { apiKeyStore.getKey("p1") } returns "sk-existing"

            viewModel.loadProvider("p1")
            advanceUntilIdle()

            val state = viewModel.editState.value
            assertEquals("p1", state.providerId)
            assertEquals("OpenAI", state.name)
            assertEquals("gpt-4o-mini", state.model)
            assertEquals("sk-existing", state.apiKey)
            assertEquals(ProviderType.OPENAI_COMPATIBLE, state.type)
            assertFalse(state.isNew)
        }

    @Test
    fun `onTypeChange re-points the base URL to the family root`() {
        viewModel.onTypeChange(ProviderType.ANTHROPIC)

        val state = viewModel.editState.value
        assertEquals(ProviderType.ANTHROPIC, state.type)
        assertEquals("https://api.anthropic.com", state.baseUrl)
        assertNull(state.verificationError)
        assertFalse(state.verificationSuccess)
    }

    @Test
    fun `onTypeChange preserves a custom base URL`() {
        viewModel.onBaseUrlChange("https://my-proxy.example.com/v1")
        viewModel.onTypeChange(ProviderType.GEMINI)

        val state = viewModel.editState.value
        assertEquals(ProviderType.GEMINI, state.type)
        assertEquals("https://my-proxy.example.com/v1", state.baseUrl)
    }

    private fun assertNull(value: Any?) {
        assertEquals(null, value)
    }
}
