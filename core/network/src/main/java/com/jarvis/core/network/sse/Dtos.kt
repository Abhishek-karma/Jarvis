package com.jarvis.core.network.sse

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    val tool_call_id: String? = null,
    val tool_calls: List<ChatCompletionRequestToolCallDto>? = null,
)

/** An assistant turn that requests tools — echoed back verbatim. */
@JsonClass(generateAdapter = true)
data class ChatCompletionRequestToolCallDto(
    val id: String,
    val type: String = "function",
    val function: ChatCompletionRequestFunctionDto,
)

@JsonClass(generateAdapter = true)
data class ChatCompletionRequestFunctionDto(
    val name: String,
    val arguments: String,
)

/** Function declaration sent as an available tool (OpenAI `tools` array). */
@JsonClass(generateAdapter = true)
data class ChatCompletionToolDto(
    val type: String = "function",
    val function: ChatCompletionFunctionDefinitionDto,
)

@JsonClass(generateAdapter = true)
data class ChatCompletionFunctionDefinitionDto(
    val name: String,
    val description: String,
    val parameters: Any? = null, // JSON-Schema object, carried by the Any adapter
)

@JsonClass(generateAdapter = true)
data class ChatCompletionRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    val stream_options: StreamOptionsDto? = StreamOptionsDto(),
    val tools: List<ChatCompletionToolDto>? = null,
)

@JsonClass(generateAdapter = true)
data class StreamOptionsDto(val include_usage: Boolean = true)

@JsonClass(generateAdapter = true)
data class ModelListResponseDto(val data: List<ModelDto>)

@JsonClass(generateAdapter = true)
data class ModelDto(val id: String)

/** One SSE chunk from an OpenAI-compatible /chat/completions stream. */
@JsonClass(generateAdapter = true)
data class ChatStreamChunkDto(
    val choices: List<ChatChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@JsonClass(generateAdapter = true)
data class ChatChoiceDto(
    val delta: DeltaDto? = null,
    val finish_reason: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<DeltaToolCallDto>? = null,
)

/** One streaming tool-call fragment — id/name arrive in the first chunk, arguments stream across chunks. */
@JsonClass(generateAdapter = true)
data class DeltaToolCallDto(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: DeltaToolCallFunctionDto? = null,
)

@JsonClass(generateAdapter = true)
data class DeltaToolCallFunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)

@JsonClass(generateAdapter = true)
data class UsageDto(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
)
