package com.jarvis.core.agent

import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
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

    private class FakeTaskRepository : TaskRepository {
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
        val runner = AgentRunner(
            registry = registry,
            audit = AuditLogger { },
            confirmationGate = { _, _ -> true },
        )
        val taskRepository = FakeTaskRepository()
        val goalEngine = GoalEngine(
            agentRunner = runner,
            taskRepository = taskRepository,
        )

        val goal = AssistantGoal(
            id = "goal-1",
            goalDescription = "What is the capital of France?",
            provider = provider,
            modelId = "test",
        )

        val events = goalEngine.executeGoal(goal).toList()
        assertTrue(events.any { it is GoalEvent.Completed })
        val completedEvent = events.first { it is GoalEvent.Completed } as GoalEvent.Completed
        assertEquals("The capital is Paris.", completedEvent.summary)
    }
}
