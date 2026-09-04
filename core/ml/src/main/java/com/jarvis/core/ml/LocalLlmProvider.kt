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

/**
 * [LlmProvider] over an on-device engine — the seam that lets LOCAL routing use the exact same
 * chat/streaming contract as cloud adapters, so the rest of the app never branches on provider identity.
 *
 * Agent mode (supportsTools = true): the model is prompted via [LocalPromptBuilder] to request a
 * tool with a single structured <tool_call>{...}</tool_call> line, which is parsed here and
 * surfaced as [ChatStreamEvent.ToolCallRequested] so the shared [com.jarvis.core.agent.AgentEngine]
 * loop drives it exactly like a cloud tool call. Plain chat streams token deltas as they arrive.
 *
 * The engine is owned by [LocalLlmRuntime] (creation is expensive and cached across calls), so
 * [close] is deliberately a no-op here.
 */
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
            supportsTools = true, // Gemma 4 E2B agent mode via the structured <tool_call> protocol
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
                // Cancellation must propagate so stop/navigate-away actually aborts the turn;
                // anything else becomes a terminal Error event, never a producer crash.
                // close() (no cause): the flow completes normally after the Error event so
                // collectors (chat stream, agent loop) handle it as data, not an exception.
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
                    // Buffer the whole reply before emitting: a <tool_call> can span partials, so we
                    // only parse once the run completes. Prose (the tool_call line stripped) streams as
                    // a single TokenDelta; a parsed tool call becomes a ToolCallRequested for the agent.
                    // StringBuffer: partials arrive on native threads, onDone on another.
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
                    // Plain chat: stream deltas live for a fast first token.
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
                // engine.generate itself threw (e.g. closed engine) — map, don't crash the flow.
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
            awaitClose { /* engine lifecycle is owned by LocalLlmRuntime */ }
        }

    override fun close() {
        // no-op — see class docs; LocalLlmRuntime closes the shared engine on model changes.
    }

    private data class ToolCall(
        val name: String,
        val args: String,
    )

    /**
     * Extracts a structured <tool_call>{"name":...,"args":{...}}</tool_call> from the reply.
     * Name comes from a targeted regex and args from balanced-brace extraction, so no JSON
     * library dependency is required for this one controlled shape.
     */
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
                '"' -> { // skip a string literal (including escapes)
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
