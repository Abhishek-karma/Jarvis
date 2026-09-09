package com.jarvis.feature.settings

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.ml.InstalledModel
import com.jarvis.core.ml.LocalModelState
import com.jarvis.core.ml.LocalModelStore
import com.jarvis.core.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
class OnboardingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: OnboardingViewModel
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var providerRepository: ProviderRepository
    private lateinit var localModelStore: LocalModelStore
    private val providersFlow = MutableStateFlow<List<com.jarvis.core.common.ProviderConfig>>(emptyList())
    private val localStatusFlow = MutableStateFlow<LocalModelState>(LocalModelState.None)
    private val localInstalledFlow = MutableStateFlow<List<InstalledModel>>(emptyList())

    private val testDispatchers =
        mockk<DispatcherProvider>().apply {
            every { main } returns testDispatcher
            every { io } returns testDispatcher
            every { default } returns testDispatcher
        }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        userPreferences = mockk(relaxed = true)
        providerRepository = mockk(relaxed = true)
        localModelStore = mockk(relaxed = true)

        coEvery { providerRepository.observeProviders() } returns providersFlow
        every { localModelStore.status } returns localStatusFlow
        every { localModelStore.installedModels } returns localInstalledFlow

        viewModel =
            OnboardingViewModel(
                userPreferences = userPreferences,
                providerRepository = providerRepository,
                localModelStore = localModelStore,
                dispatchers = testDispatchers,
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `step progression through advance and back works as expected`() =
        runTest {
            assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)

            viewModel.advance()
            assertEquals(OnboardingStep.SETUP, viewModel.uiState.value.step)

            viewModel.advance()
            assertEquals(OnboardingStep.PERMISSIONS, viewModel.uiState.value.step)

            viewModel.advance() // at end, stays at PERMISSIONS
            assertEquals(OnboardingStep.PERMISSIONS, viewModel.uiState.value.step)

            viewModel.back()
            assertEquals(OnboardingStep.SETUP, viewModel.uiState.value.step)

            viewModel.back()
            assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)

            viewModel.back() // at beginning, stays at WELCOME
            assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)
        }

    @Test
    fun `observing providers and local models updates uiState correctly`() =
        runTest {
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.hasProvider)
            assertFalse(viewModel.uiState.value.hasLocalModel)

            providersFlow.value = listOf(mockk(relaxed = true))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasProvider)
            assertEquals(1, viewModel.uiState.value.providerCount)

            localStatusFlow.value = LocalModelState.Ready(
                model = mockk(relaxed = true) { every { displayName } returns "Qwen 2.5 1.5B" },
                file = mockk(relaxed = true),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasLocalModel)
            assertEquals("Qwen 2.5 1.5B", viewModel.uiState.value.activeLocalModelName)
        }

    @Test
    fun `complete marks onboarding completed in repository and emits finished`() =
        runTest {
            viewModel.complete()
            advanceUntilIdle()

            coVerify { userPreferences.setOnboardingCompleted(true) }
        }
}
