package com.jarvis.core.network.anthropic

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Anthropic Messages API adapter (05-LLM-PROVIDERS.md §2).
 * Uses /v1/messages endpoint with SSE streaming.
 * Supports tool calling and vision.
 */
class AnthropicProvider(
    override val id: String,
    private val baseUrl: String,
    private val apiKeyProvider: () -> String?,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val dispatchers: DispatcherProvider,
) : LlmProvider {

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        vision = true,
        maxContext = 200_000,
        supportsTools = true,
        supportsReasoning = false,
    )

    private val requestAdapter = moshi.adapter(MessagesRequestDto::class.java)
    private val chunkAdapter = moshi.adapter(MessagesStreamEventDto::class.java)
    private val modelsAdapter = moshi.adapter(AnthropicModelListDto::class.java)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun streamClient(): OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(path: String): String = baseUrl.trimEnd('/') + path

    override suspend fun listModels(): Result<List<ModelInfo>> = withRetries {
        val request = Request.Builder()
            .url(buildUrl("/v1/models"))
            .header("x-api-key", apiKeyProvider() ?: "")
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()

        streamClient().newCall(request).awaitSuspending().use { response ->
            if (!response.isSuccessful) {
                throw HttpAdapterException(response.code, "HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty body")
            val parsed = modelsAdapter.fromJson(body) ?: throw IOException("Unparseable model list")
            parsed.data.map { ModelInfo(id = it.id, displayName = it.displayName ?: it.id) }
        }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val dto = MessagesRequestDto(
            model = request.model,
            maxTokens = 4096,
            messages = buildWireMessages(request),
            system = request.systemPrompt,
            stream = true,
            tools = request.toolsAvailable?.mapNotNull { definition ->
                runCatching { parseArgs(definition.parametersSchemaJson) }
                    .getOrNull()
                    ?.let { schema ->
                        ToolDto(
                            name = definition.name,
                            description = definition.description,
                            inputSchema = schema,
                        )
                    }
            }?.ifEmpty { null },
        )

        val httpRequest = Request.Builder()
            .url(buildUrl("/v1/messages"))
            .header("x-api-key", apiKeyProvider() ?: "")
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .post(requestAdapter.toJson(dto).toRequestBody(jsonMedia))
            .build()

        // Tool calls stream as tool_use blocks: id/name arrive in content_block_start,
        // arguments arrive as input_json_delta partials, and each block is emitted as one
        // ToolCallRequested when its content_block_stop lands.
        val toolBlocks = java.util.LinkedHashMap<Int, ToolUseBuffer>()

        val listener = object : EventSourceListener() {
            var currentText = ""
            var currentReasoning = ""

            override fun onOpen(eventSource: EventSource, response: Response) {
                // stream established
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                val event = runCatching { chunkAdapter.fromJson(data) }.getOrNull()
                if (event == null) return

                when (event.type) {
                    "content_block_start" -> {
                        val block = event.contentBlock
                        if (block != null && block.type == "tool_use") {
                            block.name?.let { toolName ->
                                toolBlocks[event.index ?: toolBlocks.size] = ToolUseBuffer().apply {
                                    this.name.append(toolName)
                                }
                            }
                        }
                    }
                    "content_block_delta" -> {
                        event.delta?.let { delta ->
                            when (delta.type) {
                                "text_delta" -> {
                                    delta.text?.let { text ->
                                        currentText += text
                                        trySend(ChatStreamEvent.TokenDelta(text))
                                    }
                                }
                                "thinking_delta" -> {
                                    delta.thinking?.let { thinking ->
                                        currentReasoning += thinking
                                        trySend(ChatStreamEvent.ReasoningDelta(thinking))
                                    }
                                }
                                "input_json_delta" -> {
                                    delta.partialJson?.let { partial ->
                                        toolBlocks[event.index ?: 0]?.args?.append(partial)
                                    }
                                }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        toolBlocks.remove(event.index ?: 0)?.let { block ->
                            if (block.name.isNotBlank()) {
                                trySend(
                                    ChatStreamEvent.ToolCallRequested(
                                        name = block.name.toString(),
                                        argsJson = block.args.toString(),
                                    ),
                                )
                            }
                        }
                    }
                    "message_delta" -> {
                        event.delta?.stopReason?.let { _ ->
                            // Message complete
                        }
                        event.usage?.let { usage ->
                            trySend(ChatStreamEvent.Usage(usage.inputTokens, usage.outputTokens))
                        }
                    }
                    "message_stop" -> {
                        trySend(ChatStreamEvent.Done)
                    }
                    "error" -> {
                        event.error?.let { err ->
                            val retryable = err.type == "overloaded_error" || err.type == "rate_limit_error"
                            trySend(ChatStreamEvent.Error(err.type, err.message, retryable))
                        }
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(ChatStreamEvent.Done)
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                val code = response?.code?.toString() ?: "network"
                val retryable = response?.code?.let { it == 429 || it in 500..599 } ?: true
                trySend(ChatStreamEvent.Error(code, t?.message ?: "Stream failed", retryable))
                close()
            }
        }

        val source = EventSources.createFactory(streamClient()).newEventSource(httpRequest, listener)

        awaitClose {
            source.cancel()
        }
    }.flowOn(dispatchers.io)

    override fun close() {
        // No per-stream state held
    }

    // ---- helpers ----

    /**
     * Maps domain history to Anthropic wire messages. Tool calls travel as assistant
     * `tool_use` content blocks and observations as user `tool_result` blocks (the Anthropic
     * dialect for tool results). Consecutive same-role messages — e.g. two USER turns or a
     * USER note right after a tool_result — are merged into one wire message, since the API
     * rejects adjacent messages with the same role.
     */
    private fun buildWireMessages(request: ChatRequest): List<MessageDto> {
        val wire = mutableListOf<MessageDto>()

        fun append(message: MessageDto) {
            val last = wire.lastOrNull()
            if (last != null && last.role == message.role) {
                wire[wire.lastIndex] = MessageDto(
                    role = last.role,
                    content = last.content + message.content,
                )
            } else {
                wire.add(message)
            }
        }

        request.conversationHistory.forEach { msg ->
            when (msg.role) {
                MessageRole.USER, MessageRole.SYSTEM -> // system handled via the system param
                    append(
                        MessageDto(
                            role = "user",
                            content = listOf(ContentBlockDto(type = "text", text = msg.content)),
                        ),
                    )

                MessageRole.ASSISTANT -> {
                    val blocks = mutableListOf<ContentBlockDto>()
                    if (msg.content.isNotBlank()) blocks += ContentBlockDto(type = "text", text = msg.content)
                    msg.toolCallArgsJson?.let { argsJson ->
                        val callId = msg.toolCallId
                        if (callId != null) {
                            blocks += ContentBlockDto(
                                type = "tool_use",
                                id = callId,
                                name = msg.toolCallName.orEmpty(),
                                input = parseArgs(argsJson),
                            )
                        }
                    }
                    if (blocks.isNotEmpty()) append(MessageDto(role = "assistant", content = blocks))
                }

                MessageRole.TOOL -> {
                    val callId = msg.toolCallId
                    if (callId != null) {
                        // tool_result blocks live inside a user message that follows the tool_use turn.
                        append(
                            MessageDto(
                                role = "user",
                                content = listOf(
                                    ContentBlockDto(
                                        type = "tool_result",
                                        toolUseId = callId,
                                        content = msg.content,
                                    ),
                                ),
                            ),
                        )
                    }
                    // A TOOL message without a paired call id can't be expressed in this dialect
                    // — the engine routes those notes as USER turns instead.
                }
            }
        }
        return wire
    }

    /** Parse a JSON string into a plain object tree for DTOs that take Map values. */
    private fun parseArgs(json: String?): Map<String, Any> = runCatching {
        val any = moshi.adapter(Any::class.java).fromJson(json.orEmpty())
        if (any is Map<*, *>) {
            any.entries.associate { (key, value) -> key.toString() to (value as Any) }
        } else {
            emptyMap()
        }
    }.getOrDefault(emptyMap())

    private suspend fun <T> withRetries(block: suspend () -> T): Result<T> {
        var attempt = 0
        while (true) {
            try {
                return Result.success(block())
            } catch (e: HttpAdapterException) {
                if (!e.isRetryable || attempt >= 2) return Result.failure(e)
            } catch (e: IOException) {
                if (attempt >= 2) return Result.failure(e)
            }
            delay(500L * (1L shl attempt) + Random.nextLong(0, 250))
            attempt++
        }
    }

    class HttpAdapterException(val code: Int, override val message: String) : Exception(message) {
        val isRetryable: Boolean get() = code == 429 || code in 500..599
    }
}

// ---- DTOs ----

@JsonClass(generateAdapter = true)
data class MessagesRequestDto(
    val model: String,
    @Json(name = "max_tokens") val maxTokens: Int,
    val messages: List<MessageDto>,
    val system: String? = null,
    val stream: Boolean = true,
    val tools: List<ToolDto>? = null,
    @Json(name = "tool_choice") val toolChoice: ToolChoiceDto? = null,
)

@JsonClass(generateAdapter = true)
data class MessageDto(
    val role: String, // "user" | "assistant"
    val content: List<ContentBlockDto>,
)

// One flat content-block type: the wire discriminator lives in `type` ("text", "tool_use",
// "tool_result", "image") and only the fields for that kind are set. A single generated
// adapter keeps both SSE decoding (content_block_start) and request serialization working.
@JsonClass(generateAdapter = true)
data class ContentBlockDto(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any>? = null,
    @Json(name = "tool_use_id") val toolUseId: String? = null,
    val content: String? = null,
    @Json(name = "is_error") val isError: Boolean = false,
    val source: ImageSourceDto? = null,
)

@JsonClass(generateAdapter = true)
data class ImageSourceDto(
    val type: String = "base64",
    @Json(name = "media_type") val mediaType: String,
    val data: String,
)

@JsonClass(generateAdapter = true)
data class ToolDto(
    val name: String,
    val description: String,
    @Json(name = "input_schema") val inputSchema: Map<String, Any>,
)

@JsonClass(generateAdapter = true)
data class ToolChoiceDto(
    val type: String = "auto",
    val name: String? = null,
)

/** Accumulator for one streaming tool_use block (name once, input_json partials stream). */
private class ToolUseBuffer {
    val name = StringBuilder()
    val args = StringBuilder()
}

@JsonClass(generateAdapter = true)
data class AnthropicModelListDto(
    val data: List<AnthropicModelDto>,
    @Json(name = "has_more") val hasMore: Boolean,
    @Json(name = "first_id") val firstId: String?,
    @Json(name = "last_id") val lastId: String?,
)

@JsonClass(generateAdapter = true)
data class AnthropicModelDto(
    val id: String,
    val displayName: String?,
    val type: String = "model",
)

// ---- SSE Event DTOs ----

@JsonClass(generateAdapter = true)
data class MessagesStreamEventDto(
    val type: String, // message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop, error
    val message: MessageEventDto? = null,
    val index: Int? = null,
    @Json(name = "content_block") val contentBlock: ContentBlockDto? = null,
    val delta: DeltaEventDto? = null,
    val error: ErrorEventDto? = null,
    val usage: UsageEventDto? = null,
)

@JsonClass(generateAdapter = true)
data class MessageEventDto(
    val id: String,
    val type: String = "message",
    val role: String,
    val content: List<ContentBlockDto> = emptyList(),
    val model: String,
    val stopReason: String? = null,
    val stopSequence: String? = null,
    val usage: UsageEventDto? = null,
)

@JsonClass(generateAdapter = true)
data class DeltaEventDto(
    // message_delta carries no type field — only content deltas do.
    val type: String? = null, // text_delta, thinking_delta, signature_delta, input_json_delta
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @Json(name = "partial_json") val partialJson: String? = null,
    val stopReason: String? = null,
    val stopSequence: String? = null,
)

@JsonClass(generateAdapter = true)
data class ErrorEventDto(
    val type: String, // api_error, overloaded_error, rate_limit_error, etc.
    val message: String,
)

@JsonClass(generateAdapter = true)
data class UsageEventDto(
    @Json(name = "input_tokens") val inputTokens: Int,
    @Json(name = "output_tokens") val outputTokens: Int,
)

private suspend fun Call.awaitSuspending(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}