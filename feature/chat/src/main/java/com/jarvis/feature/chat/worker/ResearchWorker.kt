package com.jarvis.feature.chat.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AssistantNotificationManager
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.TaskEngine
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.MemoryRepository
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.repository.TaskRepository
import com.jarvis.core.network.ProviderManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Isolated background research worker (Phase 7 - Advanced Intelligence).
 * Executes multi-step research asynchronously via WorkManager and AgentRunner,
 * recording structured findings into Room Task and Memory, and alerting the user upon completion.
 */
@HiltWorker
class ResearchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val providerRepository: ProviderRepository,
    private val providerManager: ProviderManager,
    private val toolRegistry: ToolRegistry,
    private val auditLogger: AuditLogger,
    private val memoryRepository: MemoryRepository,
    private val taskRepository: TaskRepository,
    private val taskEngine: TaskEngine,
    private val notifications: AssistantNotificationManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val topic = inputData.getString(KEY_TOPIC)?.trim() ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK_ID) ?: UUID.randomUUID().toString()

        val task = Task(
            id = taskId,
            title = "Research: $topic",
            goal = "Conduct isolated research on: $topic",
            triggerType = TaskTriggerType.MANUAL,
            state = TaskState.RUNNING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        taskRepository.upsert(task)
        notifications.notifyStarted(taskId, "Researching Topic", topic)

        return runCatching {
            val providers = providerRepository.observeProviders().first()
            val config = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
                ?: error("No active LLM provider configured for research.")
            val provider = providerManager.adapterFor(config)

            val runner = AgentRunner(
                registry = toolRegistry,
                audit = auditLogger,
                confirmationGate = { _, _ -> false },
                stepCap = 15,
            )

            val memories = memoryRepository.getActiveNonPrivate()
            val memoryContext = if (memories.isNotEmpty()) {
                memories.joinToString("\n") { "- [${it.category.name}]: ${it.content}" }
            } else null

            val researchPrompt = """
                You are Jarvis Research Specialist.
                Investigate the following research topic thoroughly, gather relevant details using available tools,
                and provide a synthesized, well-structured brief with clear key takeaways and recommendations.
                
                Topic: $topic
            """.trimIndent()

            val request = AgentRunRequest(
                provider = provider,
                modelId = config.model ?: "default",
                messages = listOf(
                    Message(
                        conversationId = "research-$taskId",
                        role = MessageRole.USER,
                        content = researchPrompt,
                    )
                ),
                agentRunId = taskId,
                memoryContext = memoryContext,
                planFirst = true,
            )

            var finalAnswer: String? = null
            var failureError: String? = null

            runner.run(request).collect { event ->
                when (event) {
                    is AgentEvent.FinalAnswer -> finalAnswer = event.text
                    is AgentEvent.Failed -> failureError = "${event.code}: ${event.message}"
                    is AgentEvent.ToolCancelled -> failureError = "Tool '${event.name}' required user confirmation which is unavailable in background research."
                    is AgentEvent.StepCapReached -> finalAnswer = finalAnswer ?: "Research step cap reached."
                    else -> Unit
                }
            }

            if (failureError != null) {
                taskEngine.recordFailure(taskId, failureError!!)
                notifications.notifyFailed(taskId, "Research Incomplete", "Unable to complete research on $topic.")
                Result.failure()
            } else {
                val completionText = finalAnswer ?: "Research completed."
                taskEngine.recordCompletion(taskId, completionText)

                memoryRepository.upsert(
                    Memory(
                        category = MemoryCategory.LONG_TERM_FACT,
                        content = "Research on '$topic': $completionText",
                        source = "research:$taskId",
                        confidence = 0.95f,
                    )
                )

                notifications.notifySucceeded(taskId, "Research Complete", completionText)
                Result.success()
            }
        }.getOrElse { err ->
            taskEngine.recordFailure(taskId, err.message ?: "Unknown error")
            notifications.notifyFailed(taskId, "Research Failed", err.message ?: "Unknown error")
            Result.failure()
        }
    }

    companion object {
        const val KEY_TOPIC = "topic"
        const val KEY_TASK_ID = "task_id"
    }
}
