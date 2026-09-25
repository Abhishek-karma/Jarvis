package com.jarvis.core.agent

import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.repository.OperationRepository
import com.jarvis.core.database.repository.TaskRepository
import com.jarvis.core.network.ChatStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoalEngineTest {

    private class FakeTaskRepository : TaskRepository() {
        val tasks = mutableMapOf<String, Task>()
        override fun observeAll(): Flow<List<Task>> = flowOf(tasks.values.toList())
        override fun observeByState(state: TaskState): Flow<List<Task>> = flowOf(tasks.values.filter { it.state == state })
        override suspend fun get(id: String): Task? = tasks[id]
        override suspend fun getActiveOrPendingTasks(): List<Task> = tasks.values.filter { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
        override suspend fun upsert(task: Task) { tasks[task.id] = task }
        override suspend fun updateState(id: String, state: TaskState, failureReason: String?) {
            tasks[id]?.let { tasks[id] = it.copy(state = state, failureReason = failureReason) }
        }
        override suspend fun delete(id: String) { tasks.remove(id) }
    }

    @Test
    fun `executeGoal collects AgentEvents and completes successfully`() = runTest {
        val provider = FakeLlmProvider(script = { listOf(ChatStreamEvent.TokenDelta("The capital is Paris."), ChatStreamEvent.Done) })
        val registry = ToolRegistry()
        val audit = AuditLogger { }
        val toolExecutor = ToolExecutor(registry, audit, operationRepository = OperationRepository())
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = { _, _ -> true },
            toolExecutor = toolExecutor,
        )
        val taskRepository = FakeTaskRepository()
        val goalEngine = GoalEngine(
            agentRunner = runner,
            taskRepository = taskRepository,
            toolExecutor = toolExecutor,
        )

        val goal = AssistantGoal(
            id = "goal-1",
            goalDescription = "What is the capital of France?",
            provider = provider,
            modelId = "test",
        )

        val events = goalEngine.executeGoal(goal).toList()
        assertTrue(events.any { it is GoalEvent.Completed })
        assertEquals("The capital is Paris.", (events.first { it is GoalEvent.Completed } as GoalEvent.Completed).summary)
    }

    /**
     * Regression: a goal whose last model turn carried no prose must not complete with a blank
     * summary, which previously surfaced as an empty assistant bubble next to "✓ Done".
     */
    @Test
    fun `blank model prose after a tool call still completes with a non-blank summary`() = runTest {
        val registry = ToolRegistry().apply { register(FakeTool("current_time", PermissionTier.READ_ONLY)) }
        var call = 0
        val provider =
            FakeLlmProvider(
                script = {
                    if (call++ == 0) {
                        listOf(ChatStreamEvent.ToolCallRequested("current_time", "{}"), ChatStreamEvent.Done)
                    } else {
                        listOf(ChatStreamEvent.Done)
                    }
                },
            )
        val audit = AuditLogger { }
        val toolExecutor = ToolExecutor(registry, audit, operationRepository = OperationRepository())
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = { _, _ -> true },
            toolExecutor = toolExecutor,
        )
        val goalEngine = GoalEngine(
            agentRunner = runner,
            taskRepository = FakeTaskRepository(),
            toolExecutor = toolExecutor,
        )

        val goal = AssistantGoal(
            id = "goal-blank",
            goalDescription = "What is my device status?",
            provider = provider,
            modelId = "test",
        )

        val events = goalEngine.executeGoal(goal).toList()
        val completedEvent = events.first { it is GoalEvent.Completed } as GoalEvent.Completed
        assertTrue(completedEvent.summary.isNotBlank(), "Completed.summary must never be blank")
        assertEquals("Action 'current_time' completed with result: ok.", completedEvent.summary)
        assertEquals(false, completedEvent.executionResult?.userMessage.isNullOrBlank())
    }

    @Test
    fun `needle routes simple media requests directly and completes with action summary`() = runTest {
        val registry = ToolRegistry().apply {
            register(
                com.jarvis.core.agent.tools.MediaTools.playMedia { q, app ->
                    Result.success("Playing \"$q\" on ${app ?: "default player"}.")
                },
            )
        }
        val provider = FakeLlmProvider(script = { listOf(ChatStreamEvent.Done) })
        val audit = AuditLogger { }
        val toolExecutor = ToolExecutor(registry, audit, operationRepository = OperationRepository())
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = { _, _ -> true },
            toolExecutor = toolExecutor,
        )
        val goalEngine = GoalEngine(
            agentRunner = runner,
            taskRepository = FakeTaskRepository(),
            toolExecutor = toolExecutor,
        )

        val goal = AssistantGoal(
            id = "goal-music",
            goalDescription = "Play some jazz on Spotify",
            provider = provider,
            modelId = "test",
        )

        val events = goalEngine.executeGoal(goal).toList()
        val completedEvent = events.first { it is GoalEvent.Completed } as GoalEvent.Completed
        assertEquals("Started playing \"jazz\" on spotify.", completedEvent.summary)
    }

    @Test
    fun `complex goals escalate to agent runner for multi-step reasoning`() = runTest {
        val registry = ToolRegistry()
        var turn = 0
        val provider = FakeLlmProvider(
            script = {
                listOf(
                    ChatStreamEvent.TokenDelta("Summarized tech news and created a reminder."),
                    ChatStreamEvent.Done,
                )
            },
        )
        val audit = AuditLogger { }
        val toolExecutor = ToolExecutor(registry, audit, operationRepository = OperationRepository())
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = { _, _ -> true },
            toolExecutor = toolExecutor,
        )
        val goalEngine = GoalEngine(
            agentRunner = runner,
            taskRepository = FakeTaskRepository(),
            toolExecutor = toolExecutor,
        )

        val goal = AssistantGoal(
            id = "goal-complex",
            goalDescription = "Search for tech news and then create a reminder and email summary to team",
            provider = provider,
            modelId = "test",
        )

        val events = goalEngine.executeGoal(goal).toList()
        val completedEvent = events.first { it is GoalEvent.Completed } as GoalEvent.Completed
        assertEquals("Summarized tech news and created a reminder.", completedEvent.summary)
    }
}
