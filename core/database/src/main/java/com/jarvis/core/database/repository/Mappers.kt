package com.jarvis.core.database.repository

import com.jarvis.core.common.Conversation
import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.common.ReversibleAction
import com.jarvis.core.common.Routine
import com.jarvis.core.common.RoutineScheduleType
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.entity.ConversationEntity
import com.jarvis.core.database.entity.MemoryEntity
import com.jarvis.core.database.entity.MessageEntity
import com.jarvis.core.database.entity.ProviderEntity
import com.jarvis.core.database.entity.ReversibleActionEntity
import com.jarvis.core.database.entity.RoutineEntity
import com.jarvis.core.database.entity.TaskEntity

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    providerId = providerId,
    modelId = modelId,
    routingOverride = runCatching { RoutingOverride.valueOf(routingOverride.uppercase()) }
        .getOrDefault(RoutingOverride.AUTO),
    isPrivate = isPrivate,
    branchedFromConversationId = branchedFromConversationId,
    branchedFromMessageId = branchedFromMessageId,
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    providerId = providerId,
    modelId = modelId,
    routingOverride = when (routingOverride) {
        RoutingOverride.AUTO -> "auto"
        RoutingOverride.LOCAL -> "local"
        RoutingOverride.CLOUD -> "cloud"
    },
    isPrivate = isPrivate,
    branchedFromConversationId = branchedFromConversationId,
    branchedFromMessageId = branchedFromMessageId,
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = runCatching { MessageRole.valueOf(role.uppercase()) }.getOrDefault(MessageRole.USER),
    content = content,
    reasoningContent = reasoningContent,
    createdAt = createdAt,
    editedAt = editedAt,
    status = runCatching { MessageStatus.valueOf(status.uppercase()) }
        .getOrDefault(MessageStatus.COMPLETE),
    routeUsed = routeUsed,
    errorHint = errorHint,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name.lowercase(),
    content = content,
    reasoningContent = reasoningContent,
    createdAt = createdAt,
    editedAt = editedAt,
    status = status.name.lowercase(),
    routeUsed = routeUsed,
    errorHint = errorHint,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
)

fun ProviderEntity.toDomain(): ProviderConfig = ProviderConfig(
    id = id,
    name = name,
    baseUrl = baseUrl,
    model = model,


    type = runCatching { ProviderType.valueOf(type.uppercase()) }
        .getOrDefault(ProviderType.OPENAI_COMPATIBLE),
    isDefault = isDefault,
)

fun ProviderConfig.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    name = name,
    baseUrl = baseUrl,
    model = model,
    type = type.name.lowercase(),
    isDefault = isDefault,
)

fun MemoryEntity.toDomain(): Memory = Memory(
    id = id,
    category = runCatching { MemoryCategory.valueOf(category.uppercase()) }
        .getOrDefault(MemoryCategory.LONG_TERM_FACT),
    content = content,
    source = source,
    confidence = confidence,
    timestamp = timestamp,
    isPrivate = isPrivate,
    isActive = isActive,
)

fun Memory.toEntity(): MemoryEntity = MemoryEntity(
    id = id,
    category = category.name,
    content = content,
    source = source,
    confidence = confidence,
    timestamp = timestamp,
    isPrivate = isPrivate,
    isActive = isActive,
)

fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    goal = goal,
    triggerType = runCatching { TaskTriggerType.valueOf(triggerType.uppercase()) }
        .getOrDefault(TaskTriggerType.MANUAL),
    triggerConfigJson = triggerConfigJson,
    state = runCatching { TaskState.valueOf(state.uppercase()) }
        .getOrDefault(TaskState.QUEUED),
    stepsJson = stepsJson,
    requiredPermissionsJson = requiredPermissionsJson,
    retries = retries,
    maxRetries = maxRetries,
    resultJson = resultJson,
    failureReason = failureReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    goal = goal,
    triggerType = triggerType.name,
    triggerConfigJson = triggerConfigJson,
    state = state.name,
    stepsJson = stepsJson,
    requiredPermissionsJson = requiredPermissionsJson,
    retries = retries,
    maxRetries = maxRetries,
    resultJson = resultJson,
    failureReason = failureReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
)

fun RoutineEntity.toDomain(): Routine = Routine(
    id = id,
    name = name,
    goal = goal,
    scheduleType = runCatching { RoutineScheduleType.valueOf(scheduleType.uppercase()) }
        .getOrDefault(RoutineScheduleType.RECURRING),
    cronOrInterval = cronOrInterval,
    nextRunAt = nextRunAt,
    enabled = enabled,
    lastRunAt = lastRunAt,
    lastRunStatus = lastRunStatus,
    failureReason = failureReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Routine.toEntity(): RoutineEntity = RoutineEntity(
    id = id,
    name = name,
    goal = goal,
    scheduleType = scheduleType.name,
    cronOrInterval = cronOrInterval,
    nextRunAt = nextRunAt,
    enabled = enabled,
    lastRunAt = lastRunAt,
    lastRunStatus = lastRunStatus,
    failureReason = failureReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ReversibleActionEntity.toDomain(): ReversibleAction = ReversibleAction(
    id = id,
    actionType = actionType,
    target = target,
    inverseActionJson = inverseActionJson,
    createdAt = createdAt,
    isReverted = isReverted,
)

fun ReversibleAction.toEntity(): ReversibleActionEntity = ReversibleActionEntity(
    id = id,
    actionType = actionType,
    target = target,
    inverseActionJson = inverseActionJson,
    createdAt = createdAt,
    isReverted = isReverted,
)
