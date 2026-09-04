package com.jarvis.core.network.gemini

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
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
 * Google Gemini API adapter (05-LLM-PROVIDERS.md §2).
 * Uses generateContent endpoint with SSE streaming.
 * Supports vision and tool calling.
 */
class GeminiProvider(
    override val id: String,
    private val baseUrl: String,
    private val apiKeyProvider: () -> String?,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val dispatchers: DispatcherProvider,
) : LlmProvider {

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        vision = true,
        maxContext = 1_000_000,
        supportsTools = true,
        supportsReasoning = false,
    )

    private val requestAdapter = moshi.adapter(GenerateContentRequest::class.java)
    private val chunkAdapter = moshi.adapter(GenerateContentResponse::class.java)
    private val modelsAdapter = moshi.adapter(ModelsListResponse::class.java)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun streamClient(): OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(path: String): String = baseUrl.trimEnd('/') + path

    override suspend fun listModels(): Result<List<ModelInfo>> = withRetries {
        val request = Request.Builder()
            .url(buildUrl("/v1/models?key=${apiKeyProvider() ?: ""}"))
            .get()
            .build()

        streamClient().newCall(request).awaitSuspending().use { response ->
            if (!response.isSuccessful) {
                throw HttpAdapterException(response.code, "HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty body")
            val parsed = modelsAdapter.fromJson(body) ?: throw IOException("Unparseable model list")
            parsed.models
                .filter { it.supportedGenerationMethods.contains("generateContent") }
                .map { ModelInfo(id = it.name, displayName = it.displayName) }
        }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val dto = GenerateContentRequest(
            contents = buildWireContents(request),
            systemInstruction = request.systemPrompt?.let { Content(parts = listOf(Part(text = it))) },
            generationConfig = GenerationConfig(
                responseMimeType = "text/plain",
            ),
            tools = request.toolsAvailable?.mapNotNull { definition ->
                runCatching { parseArgs(definition.parametersSchemaJson) }
                    .getOrNull()
                    ?.let { schema ->
                        Tool(
                            functionDeclarations = listOf(
                                FunctionDeclaration(
                                    name = definition.name,
                                    description = definition.description,
                                    parameters = schema,
                                ),
                            ),
                        )
                    }
            }?.ifEmpty { null },
        )

        val modelId = request.model.ifEmpty { "gemini-2.0-flash" }
        val httpRequest = Request.Builder()
            .url(buildUrl("/v1/models/${modelId}:streamGenerateContent?alt=sse&key=${apiKeyProvider() ?: ""}"))
            .header("Accept", "text/event-stream")
            .post(requestAdapter.toJson(dto).toRequestBody(jsonMedia))
            .build()

        // Function calls arrive as parts; large args can span chunks, so merge by name and
        // emit once the stream finishes (the engine consumes the full flow before acting).
        val functionCalls = java.util.LinkedHashMap<String, MutableMap<String, Any>>()
        var functionCallsFlushed = false

        fun flushFunctionCalls() {
            if (functionCallsFlushed) return
            functionCallsFlushed = true
            functionCalls.forEach { (name, args) ->
                if (args.isNotEmpty()) {
                    trySend(
                        ChatStreamEvent.ToolCallRequested(
                            name = name,
                            argsJson = compactJson(args),
                        ),
                    )
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
                if (chunk == null) return

                // Extract text and function calls from candidates
                chunk.candidates?.forEach { candidate ->
                    candidate.content?.parts?.forEach { part ->
                        part.text?.let { text ->
                            trySend(ChatStreamEvent.TokenDelta(text))
                        }
                        part.functionCall?.let { call ->
                            val merged = functionCalls.getOrPut(call.name) { java.util.LinkedHashMap() }
                            call.args.forEach { (key, value) -> merged[key] = value }
                        }
                    }

                    // A STOP chunk signals the turn is done — flush any pending calls.
                    candidate.finishReason?.takeIf { it == "STOP" }?.let { flushFunctionCalls() }
                }

                // Handle usage metadata
                chunk.usageMetadata?.let { usage ->
                    if (usage.promptTokenCount > 0 || usage.candidatesTokenCount > 0) {
                        trySend(ChatStreamEvent.Usage(usage.promptTokenCount, usage.candidatesTokenCount))
                    }
                }

                // Handle errors
                chunk.promptFeedback?.blockReason?.let { reason ->
                    trySend(ChatStreamEvent.Error(reason, "Prompt blocked: $reason", false))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                flushFunctionCalls()
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
     * Maps domain history to Gemini contents. Tool calls become `functionCall` parts on model
     * turns and observations become `functionResponse` parts on the following user turn — the
     * dialect Gemini requires (05-LLM-PROVIDERS.md §2).
     */
    private fun buildWireContents(request: ChatRequest): List<Content> {
        val contents = mutableListOf<Content>()
        request.conversationHistory.forEach { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "model"
                MessageRole.SYSTEM -> "user"
                MessageRole.TOOL -> "user"
            }
            val parts = mutableListOf<Part>()
            when (msg.role) {
                MessageRole.ASSISTANT -> {
                    if (msg.content.isNotBlank()) parts += Part(text = msg.content)
                    msg.toolCallArgsJson?.let { argsJson ->
                        val callId = msg.toolCallId
                        if (callId != null) {
                            parts += Part(
                                functionCall = FunctionCall(
                                    name = msg.toolCallName.orEmpty(),
                                    args = parseArgs(argsJson),
                                ),
                            )
                        }
                    }
                }
                MessageRole.TOOL -> {
                    val callId = msg.toolCallId
                    if (callId != null) {
                        // Gemini wraps the observation in a response object keyed by the function name.
                        parts += Part(
                            functionResponse = FunctionResponse(
                                name = msg.toolCallName.orEmpty(),
                                response = mapOf("result" to msg.content),
                            ),
                        )
                    }
                    // A TOOL message without a paired call id can't be expressed here — the
                    // engine routes those notes as USER turns instead.
                }
                else -> parts += Part(text = msg.content)
            }
            if (parts.isNotEmpty()) contents += Content(role = role, parts = parts)
        }
        return contents
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

    /** Serialize a plain object tree back to a compact JSON string (for ToolCallRequested). */
    private fun compactJson(value: Any): String = moshi.adapter(Any::class.java).toJson(value)

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
data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null,
    val tools: List<Tool>? = null,
    val toolConfig: ToolConfig? = null,
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>,
    val role: String? = null,
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null,
    val functionCall: FunctionCall? = null,
    val functionResponse: FunctionResponse? = null,
)

@JsonClass(generateAdapter = true)
data class InlineData(
    val mimeType: String,
    val data: String,
)

@JsonClass(generateAdapter = true)
data class FunctionCall(
    val name: String,
    val args: Map<String, Any>,
)

@JsonClass(generateAdapter = true)
data class FunctionResponse(
    val name: String,
    val response: Map<String, Any>,
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val responseSchema: Map<String, Any>? = null,
)

@JsonClass(generateAdapter = true)
data class Tool(
    val functionDeclarations: List<FunctionDeclaration>,
)

@JsonClass(generateAdapter = true)
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
)

@JsonClass(generateAdapter = true)
data class ToolConfig(
    val functionCallingConfig: FunctionCallingConfig,
)

@JsonClass(generateAdapter = true)
data class FunctionCallingConfig(
    val mode: String, // AUTO, NONE, ANY
)

// ---- Response DTOs ----

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val usageMetadata: UsageMetadata? = null,
    val promptFeedback: PromptFeedback? = null,
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val index: Int,
    val content: Content? = null,
    val finishReason: String? = null,
    val finishMessage: String? = null,
    val safetyRatings: List<SafetyRating>? = null,
)

@JsonClass(generateAdapter = true)
data class UsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val totalTokenCount: Int = 0,
)

@JsonClass(generateAdapter = true)
data class PromptFeedback(
    val blockReason: String? = null,
    val blockReasonMessage: String? = null,
    val safetyRatings: List<SafetyRating>? = null,
)

@JsonClass(generateAdapter = true)
data class SafetyRating(
    val category: String,
    val probability: String,
    val blocked: Boolean = false,
)

// ---- Models List DTOs ----

@JsonClass(generateAdapter = true)
data class ModelsListResponse(
    val models: List<GeminiModel>,
    val nextPageToken: String? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiModel(
    val name: String,
    val displayName: String,
    val description: String? = null,
    val supportedGenerationMethods: List<String> = emptyList(),
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