package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.TaskRepository
import java.util.UUID

object TaskTools {
    const val CREATE_TASK = "create_task"
    const val GET_TASK_STATUS = "get_task_status"
    const val LIST_TASKS = "list_tasks"
    const val CANCEL_TASK = "cancel_task"

    val manifestNames: List<String> = listOf(CREATE_TASK, GET_TASK_STATUS, LIST_TASKS, CANCEL_TASK)

    private const val CREATE_SCHEMA = """{
  "type": "object",
  "properties": {
    "title": {"type": "string", "description": "Short title describing the task."},
    "goal": {"type": "string", "description": "High-level goal the agent must accomplish."},
    "steps_json": {"type": "string", "description": "Optional JSON array of step descriptions."},
    "trigger_type": {"type": "string", "enum": ["MANUAL", "SCHEDULED", "ROUTINE"], "description": "Trigger origin."}
  },
  "required": ["title", "goal"]
}"""

    private const val STATUS_SCHEMA = """{
  "type": "object",
  "properties": {
    "task_id": {"type": "string", "description": "ID of the task to inspect."}
  },
  "required": ["task_id"]
}"""

    private const val LIST_SCHEMA = """{
  "type": "object",
  "properties": {
    "state": {"type": "string", "enum": ["SCHEDULED", "QUEUED", "RUNNING", "WAITING_FOR_CONFIRMATION", "COMPLETED", "FAILED", "CANCELLED"], "description": "Filter by task state."}
  }
}"""

    private const val CANCEL_SCHEMA = """{
  "type": "object",
  "properties": {
    "task_id": {"type": "string", "description": "ID of the task to cancel."}
  },
  "required": ["task_id"]
}"""

    fun all(repository: TaskRepository): List<Tool> = listOf(
        createTask(repository),
        getTaskStatus(repository),
        listTasks(repository),
        cancelTask(repository),
    )

    fun createTask(repository: TaskRepository): Tool =
        object : Tool {
            override val name = CREATE_TASK
            override val description = "Creates a durable, multi-step task that can run or resume across app lifecycles."
            override val parametersSchemaJson = CREATE_SCHEMA
            override val tier = PermissionTier.REVERSIBLE_WRITE

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(success = false, observationText = "Invalid arguments", error = "Malformed JSON")
                val title = args.string("title")
                    ?: return ToolResult(success = false, observationText = "Missing 'title'", error = "title is required")
                val goal = args.string("goal")
                    ?: return ToolResult(success = false, observationText = "Missing 'goal'", error = "goal is required")
                val stepsJson = args.string("steps_json") ?: "[]"
                val triggerTypeStr = args.string("trigger_type") ?: "MANUAL"
                val triggerType = runCatching { TaskTriggerType.valueOf(triggerTypeStr.uppercase()) }
                    .getOrDefault(TaskTriggerType.MANUAL)

                val task = Task(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    goal = goal,
                    triggerType = triggerType,
                    state = TaskState.QUEUED,
                    stepsJson = stepsJson,
                    requiredPermissionsJson = "[]",
                    retries = 0,
                    maxRetries = 3,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                repository.upsert(task)

                return ToolResult(
                    success = true,
                    observationText = "Created task [${task.id}] \"${task.title}\" with state QUEUED.",
                    structuredData = mapOf("id" to task.id, "title" to task.title, "state" to task.state.name),
                )
            }
        }

    fun getTaskStatus(repository: TaskRepository): Tool =
        object : Tool {
            override val name = GET_TASK_STATUS
            override val description = "Gets the current execution status and details of a task."
            override val parametersSchemaJson = STATUS_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(success = false, observationText = "Invalid arguments", error = "Malformed JSON")
                val taskId = args.string("task_id")
                    ?: return ToolResult(success = false, observationText = "Missing 'task_id'", error = "task_id is required")

                val task = repository.get(taskId)
                    ?: return ToolResult(success = false, observationText = "Task not found: $taskId", error = "Not found")

                return ToolResult(
                    success = true,
                    observationText = "Task [${task.id}] \"${task.title}\": state=${task.state}, retries=${task.retries}/${task.maxRetries}, goal=\"${task.goal}\"" +
                            (if (task.failureReason != null) ", error: ${task.failureReason}" else ""),
                    structuredData = mapOf("id" to task.id, "state" to task.state.name, "title" to task.title),
                )
            }
        }

    fun listTasks(repository: TaskRepository): Tool =
        object : Tool {
            override val name = LIST_TASKS
            override val description = "Lists current tasks, optionally filtered by state."
            override val parametersSchemaJson = LIST_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val stateStr = args?.string("state")
                val state = stateStr?.let { runCatching { TaskState.valueOf(it.uppercase()) }.getOrNull() }

                val tasks = if (state != null) {
                    repository.getActiveOrPendingTasks().filter { it.state == state }
                } else {
                    repository.getActiveOrPendingTasks()
                }

                if (tasks.isEmpty()) {
                    return ToolResult(success = true, observationText = "No matching tasks found.", structuredData = mapOf("count" to 0))
                }

                val listText = tasks.joinToString("\n") {
                    "- [${it.id}] (${it.state}): ${it.title} - ${it.goal}"
                }
                return ToolResult(
                    success = true,
                    observationText = "Found ${tasks.size} tasks:\n$listText",
                    structuredData = mapOf("count" to tasks.size),
                )
            }
        }

    fun cancelTask(repository: TaskRepository): Tool =
        object : Tool {
            override val name = CANCEL_TASK
            override val description = "Cancels an ongoing or scheduled task."
            override val parametersSchemaJson = CANCEL_SCHEMA
            override val tier = PermissionTier.REVERSIBLE_WRITE

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(success = false, observationText = "Invalid arguments", error = "Malformed JSON")
                val taskId = args.string("task_id")
                    ?: return ToolResult(success = false, observationText = "Missing 'task_id'", error = "task_id is required")

                val existing = repository.get(taskId)
                    ?: return ToolResult(success = false, observationText = "Task not found: $taskId", error = "Not found")

                repository.updateState(taskId, TaskState.CANCELLED, "Cancelled by user or agent request")
                return ToolResult(
                    success = true,
                    observationText = "Cancelled task [${existing.id}] \"${existing.title}\".",
                    structuredData = mapOf("id" to taskId, "state" to TaskState.CANCELLED.name),
                )
            }
        }
}
