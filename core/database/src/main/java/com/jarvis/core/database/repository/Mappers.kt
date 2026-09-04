package com.jarvis.core.database.repository

import com.jarvis.core.common.Conversation
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.database.entity.ConversationEntity
import com.jarvis.core.database.entity.MessageEntity
import com.jarvis.core.database.entity.ProviderEntity

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
    type = ProviderType.OPENAI_COMPATIBLE,
    isDefault = isDefault,
)

fun ProviderConfig.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    name = name,
    baseUrl = baseUrl,
    type = type.name.lowercase(),
    isDefault = isDefault,
)
