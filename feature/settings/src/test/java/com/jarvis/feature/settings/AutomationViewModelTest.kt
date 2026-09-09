package com.jarvis.feature.settings

import com.jarvis.core.agent.RoutineScheduler
import com.jarvis.core.agent.TaskEngine
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Routine
import com.jarvis.core.common.RoutineScheduleType
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.RoutineRepository
import com.jarvis.core.database.repository.TaskRepository
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
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val routineRepository: RoutineRepository = mockk(relaxed = true)
    private val taskRepository: TaskRepository = mockk(relaxed = true)
    private val routineScheduler: RoutineScheduler = mockk(relaxed = true)
    private val taskEngine: TaskEngine = mockk(relaxed = true)

    private val routinesFlow = MutableStateFlow<List<Routine>>(emptyList())
    private val tasksFlow = MutableStateFlow<List<Task>>(emptyList())

    private val testDispatchers = mockk<DispatcherProvider>().apply {
        every { main } returns testDispatcher
        every { io } returns testDispatcher
        every { default } returns testDispatcher
    }

    private lateinit var viewModel: AutomationViewModel
    private var uiStateJob: kotlinx.coroutines.Job? = null

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { routineRepository.observeAll() } returns routinesFlow
        every { taskRepository.observeAll() } returns tasksFlow

        viewModel = AutomationViewModel(
            routineRepository = routineRepository,
            taskRepository = taskRepository,
            routineScheduler = routineScheduler,
            taskEngine = taskEngine,
            dispatchers = testDispatchers,
        )
        uiStateJob = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.uiState.collect {}
        }
    }

    @AfterEach
    fun tearDown() {
        uiStateJob?.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `selectTab updates selectedTab in uiState`() = runTest(testDispatcher) {
        viewModel.selectTab(1)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun `setTaskStateFilter filters visible tasks`() = runTest(testDispatcher) {
        val task1 = Task(
            id = "t1",
            title = "Task 1",
            goal = "Goal 1",
            triggerType = TaskTriggerType.MANUAL,
            state = TaskState.RUNNING,
            createdAt = 100L,
            updatedAt = 100L,
        )
        val task2 = Task(
            id = "t2",
            title = "Task 2",
            goal = "Goal 2",
            triggerType = TaskTriggerType.MANUAL,
            state = TaskState.COMPLETED,
            createdAt = 200L,
            updatedAt = 200L,
        )
        tasksFlow.value = listOf(task1, task2)
        advanceUntilIdle()

        viewModel.setTaskStateFilter(TaskState.RUNNING)
        advanceUntilIdle()

        assertEquals(listOf(task1), viewModel.uiState.value.tasks)
        assertEquals(TaskState.RUNNING, viewModel.uiState.value.selectedTaskStateFilter)
    }

    @Test
    fun `runRoutineNow triggers scheduler and updates isRunning state`() = runTest(testDispatcher) {
        coEvery { routineScheduler.runNow("r1") } returns Result.success("Success!")

        viewModel.runRoutineNow("r1")
        advanceUntilIdle()

        coVerify { routineScheduler.runNow("r1") }
        assertNull(viewModel.uiState.value.isRunningRoutineId)
    }

    @Test
    fun `toggleRoutineEnabled delegates to routineScheduler`() = runTest(testDispatcher) {
        viewModel.toggleRoutineEnabled("r1", true)
        advanceUntilIdle()

        coVerify { routineScheduler.setEnabled("r1", true) }
    }

    @Test
    fun `deleteRoutine cancels in scheduler and deletes from repository`() = runTest(testDispatcher) {
        viewModel.deleteRoutine("r1")
        advanceUntilIdle()

        coVerify { routineScheduler.cancel("r1") }
        coVerify { routineRepository.delete("r1") }
    }

    @Test
    fun `cancelTask delegates to taskEngine`() = runTest(testDispatcher) {
        viewModel.cancelTask("t1")
        advanceUntilIdle()

        coVerify { taskEngine.cancelTask("t1") }
    }
}
