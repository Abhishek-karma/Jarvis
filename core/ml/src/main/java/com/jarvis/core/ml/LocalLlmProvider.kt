package com.jarvis.core.ml

import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow


class LocalLlmProvider(
    override val id: String,
    private val spec: LocalModelSpec,
    private val engine: OnDeviceEngine,
) : LlmProvider {
    /** ChatRequest.model value for local requests (informational — the prompt is single-shot). */
    val modelId: String = spec.id

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            vision = false,
            maxContext = 2_048,
            supportsTools = true,
            supportsReasoning = false,
        )

    override suspend fun listModels(): Result<List<ModelInfo>> =
        Result.success(listOf(ModelInfo(id = spec.id, displayName = spec.displayName)))

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> =
        callbackFlow {
            val prompt = runCatching { LocalPromptBuilder.build(request) }.getOrNull().orEmpty()
            if (prompt.isBlank()) {
                trySend(
                    ChatStreamEvent.Error(
                        code = "local",
                        message = "Empty prompt for on-device model",
                        retryable = false,
                    ),
                )
                close()
                return@callbackFlow
            }

            fun finishWithError(error: Throwable) {




                if (error is CancellationException) throw error
                trySend(
                    ChatStreamEvent.Error(
                        code = "local",
                        message = error.message ?: "On-device inference failed",
                        retryable = false,
                    ),
                )
                close()
            }

            try {
                val agentMode = !request.toolsAvailable.isNullOrEmpty()
                if (agentMode) {




                    val buffer = StringBuffer()
                    engine.generate(
                        prompt = prompt,
                        onPartial = { text -> buffer.append(text) },
                        onDone = {
                            val full = buffer.toString()
                            val call = runCatching { parseToolCall(full) }.getOrNull()
                            if (call != null) {
                                val prose = TOOL_CALL_REGEX.replace(full, "").trim()
                                if (prose.isNotEmpty()) trySend(ChatStreamEvent.TokenDelta(prose))
                                trySend(ChatStreamEvent.ToolCallRequested(call.name, call.args))
                            } else if (full.isNotEmpty()) {
                                trySend(ChatStreamEvent.TokenDelta(full))
                            }
                            trySend(ChatStreamEvent.Done)
                            close()
                        },
                        onError = ::finishWithError,
                    )
                } else {

                    engine.generate(
                        prompt = prompt,
                        onPartial = { text -> trySend(ChatStreamEvent.TokenDelta(text)) },
                        onDone = {
                            trySend(ChatStreamEvent.Done)
                            close()
                        },
                        onError = ::finishWithError,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {

                trySend(
                    ChatStreamEvent.Error(
                        code = "local",
                        message = t.message ?: "On-device inference failed",
                        retryable = false,
                    ),
                )
                close()
                return@callbackFlow
            }
            awaitClose {  }
        }

    override fun close() {

    }

    private data class ToolCall(
        val name: String,
        val args: String,
    )


    private fun parseToolCall(fullText: String): ToolCall? {
        val match = TOOL_CALL_REGEX.find(fullText) ?: return null
        val name = NAME_REGEX.find(match.groupValues[1])?.groupValues?.get(1) ?: return null
        val args = extractArgs(match.groupValues[1]) ?: "{}"
        return ToolCall(name, args)
    }

    /** Returns the balanced JSON object value following the `"args":` key, or null. */
    private fun extractArgs(jsonText: String): String? {
        val keyIdx = jsonText.indexOf("\"args\"")
        if (keyIdx < 0) return null
        val colon = jsonText.indexOf(':', keyIdx)
        if (colon < 0) return null
        var i = colon + 1
        while (i < jsonText.length && jsonText[i].isWhitespace()) i++
        if (i >= jsonText.length || jsonText[i] != '{') return null
        var depth = 0
        var j = i
        while (j < jsonText.length) {
            when (jsonText[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return jsonText.substring(i, j + 1)
                }
                '"' -> {
                    j++
                    while (j < jsonText.length) {
                        when (jsonText[j]) {
                            '\\' -> j += 2
                            '"' -> break
                            else -> j++
                        }
                    }
                }
            }
            j++
        }
        return null
    }

    private companion object {
        val TOOL_CALL_REGEX =
            Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        val NAME_REGEX = Regex("""\"name\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"""")
    }
}
