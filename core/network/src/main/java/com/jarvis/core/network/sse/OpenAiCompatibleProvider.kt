package com.jarvis.core.network.sse

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
import com.jarvis.core.network.apiRoot
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
 * OpenAI-compatible adapter — works for OpenAI, Mistral, Groq, xAI,
 * LM Studio, Ollama's OpenAI endpoint, and any custom/self-hosted server speaking the
 * /v1/chat/completions + /v1/models dialect.
 *
 * Streaming uses OkHttp SSE. Retry policy: exponential backoff
 * (500ms base ×2, jittered), max 3 attempts, only for idempotent errors (timeout/429/5xx),
 * never for 4xx auth errors. Cancelling collection aborts the socket via invokeOnCancellation.
 *
 * Instances are created per ProviderConfig by ProviderManager (not Hilt-injectable directly,
 * since id/baseUrl/key-access are runtime values).
 */
class OpenAiCompatibleProvider(
    override val id: String,
    private val baseUrl: String,
    private val apiKeyProvider: () -> String?,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val dispatchers: DispatcherProvider,
) : LlmProvider {

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        vision = false,
        maxContext = 128_000,
        supportsTools = true,
        supportsReasoning = true, // reasoning_content delta support (DeepSeek-style)
    )

    private val chatAdapter = moshi.adapter(ChatCompletionRequestDto::class.java)
    private val chunkAdapter = moshi.adapter(ChatStreamChunkDto::class.java)
    private val modelsAdapter = moshi.adapter(ModelListResponseDto::class.java)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun streamClient(): OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // long generations can pause between chunks
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(path: String): String = apiRoot(baseUrl) + path

    override suspend fun listModels(): Result<List<ModelInfo>> = withRetries {
        val request = Request.Builder()
            .url(buildUrl("/v1/models"))
            .header("Authorization", "Bearer ${apiKeyProvider() ?: ""}")
            .get()
            .build()

        streamClient().newCall(request).awaitSuspending().use { response ->
            if (!response.isSuccessful) {
                throw HttpAdapterException(response.code, "HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty body")
            val parsed = modelsAdapter.fromJson(body) ?: throw IOException("Unparseable model list")
            parsed.data.map { ModelInfo(id = it.id, displayName = it.id) }
        }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val dto = ChatCompletionRequestDto(
            model = request.model,
            messages = buildWireMessages(request),
            tools = request.toolsAvailable?.mapNotNull { definition ->
                val parameters = runCatching {
                    moshi.adapter(Any::class.java).fromJson(definition.parametersSchemaJson)
                }.getOrNull()
                ChatCompletionToolDto(
                    function = ChatCompletionFunctionDefinitionDto(
                        name = definition.name,
                        description = definition.description,
                        parameters = parameters,
                    ),
                )
            }?.ifEmpty { null },
        )
        val httpRequest = Request.Builder()
            .url(buildUrl("/v1/chat/completions"))
            .header("Authorization", "Bearer ${apiKeyProvider() ?: ""}")
            .header("Accept", "text/event-stream")
            .post(chatAdapter.toJson(dto).toRequestBody(jsonMedia))
            .build()

        // Streaming tool_calls arrive as index-keyed fragments: id + name in the first chunk
        // for that index, arguments appended piecewise. Emitted once the stream completes.
        val toolCalls = LinkedHashMap<Int, ToolCallBuffer>()
        var toolCallsFlushed = false

        fun flushToolCalls() {
            if (toolCallsFlushed) return
            toolCallsFlushed = true
            toolCalls.values.forEach { buffer ->
                if (buffer.name.isNotBlank()) {
                    trySend(ChatStreamEvent.ToolCallRequested(buffer.name.toString(), buffer.args.toString()))
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // stream established
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                val chunk = runCatching { chunkAdapter.fromJson(data) }.getOrNull()
                if (chunk == null) {
                    // Skip malformed keep-alive/heartbeat chunks rather than killing the stream.
                    if (data == DONE_MARKER) {
                        flushToolCalls()
                        trySend(ChatStreamEvent.Done)
                    }
                    return
                }
                chunk.choices.forEach { choice ->
                    choice.delta?.reasoning_content?.let {
                        trySend(ChatStreamEvent.ReasoningDelta(it))
                    }
                    choice.delta?.content?.let { trySend(ChatStreamEvent.TokenDelta(it)) }
                    choice.delta?.tool_calls?.forEach { deltaCall ->
                        val buffer = toolCalls.getOrPut(deltaCall.index) { ToolCallBuffer() }
                        deltaCall.function?.name?.let { buffer.name.append(it) }
                        deltaCall.function?.arguments?.let { buffer.args.append(it) }
                    }
                }
                chunk.usage?.let {
                    trySend(ChatStreamEvent.Usage(it.prompt_tokens ?: 0, it.completion_tokens ?: 0))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                flushToolCalls()
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

        // EventSources.createFactory creates and manages its own call from the Request.
        val source = EventSources.createFactory(streamClient()).newEventSource(httpRequest, listener)

        awaitClose {
            // Cancellation: genuinely abort the socket.
            source.cancel()
        }
    }.flowOn(dispatchers.io)

    override fun close() {
        // This adapter holds no per-stream state; per-stream cleanup happens in awaitClose.
    }

    // ---- helpers ----

    private fun buildWireMessages(request: ChatRequest): List<ChatMessageDto> {
        val wire = mutableListOf<ChatMessageDto>()
        request.systemPrompt?.let { wire.add(ChatMessageDto(role = "system", content = it)) }
        request.conversationHistory.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> wire.add(ChatMessageDto(role = "user", content = msg.content))
                MessageRole.SYSTEM -> wire.add(ChatMessageDto(role = "system", content = msg.content))
                MessageRole.ASSISTANT -> {
                    val toolCallArgs = msg.toolCallArgsJson
                    if (toolCallArgs != null) {
                        // Echo the tool call so the server sees the assistant turn that requested it.
                        wire.add(
                            ChatMessageDto(
                                role = "assistant",
                                content = null,
                                tool_calls = listOf(
                                    ChatCompletionRequestToolCallDto(
                                        id = msg.toolCallId.orEmpty(),
                                        function = ChatCompletionRequestFunctionDto(
                                            name = msg.toolCallName.orEmpty(),
                                            arguments = toolCallArgs,
                                        ),
                                    ),
                                ),
                            ),
                        )
                    } else {
                        wire.add(ChatMessageDto(role = "assistant", content = msg.content))
                    }
                }
                MessageRole.TOOL -> {
                    val callId = msg.toolCallId
                    if (callId != null) {
                        wire.add(
                            ChatMessageDto(
                                role = "tool",
                                content = msg.content,
                                name = msg.toolCallName,
                                tool_call_id = callId,
                            ),
                        )
                    }
                    // A TOOL message without a paired call id can't be expressed in the OpenAI
                    // dialect — the engine routes those notes as USER turns instead.
                }
            }
        }
        return wire
    }

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

    /** HTTP-level failure carrying a classifiable status code. */
    class HttpAdapterException(val code: Int, override val message: String) : Exception(message) {
        val isRetryable: Boolean get() = code == 429 || code in 500..599
    }

    private companion object {
        const val DONE_MARKER = "[DONE]"
    }
}

/** Index-keyed accumulator for one streaming tool-call (name arrives once, arguments stream). */
private class ToolCallBuffer {
    val name = StringBuilder()
    val args = StringBuilder()
}

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
