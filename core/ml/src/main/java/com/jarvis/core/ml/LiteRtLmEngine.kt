package com.jarvis.core.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LiteRtLmEngine"

class LiteRtLmEngine private constructor(
    private val engine: Engine,
) : OnDeviceEngine {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val gson = Gson()

    override fun streamChat(
        conversationHistory: List<Message>,
        systemPrompt: String?,
        tools: List<ToolDefinition>?,
        temperature: Double?,
    ): Flow<ChatStreamEvent> = callbackFlow {
        if (closed.get()) {
            trySend(ChatStreamEvent.Error(code = "local", message = "On-device engine is closed", retryable = false))
            close()
            return@callbackFlow
        }

        if (conversationHistory.isEmpty()) {
            trySend(ChatStreamEvent.Done)
            close()
            return@callbackFlow
        }

        mutex.withLock {
            if (closed.get()) {
                trySend(ChatStreamEvent.Error(code = "local", message = "On-device engine is closed", retryable = false))
                close()
                return@withLock
            }

            val toolProviders = tools?.map { tool(LiteRtLmOpenApiTool(it)) }.orEmpty()
            val nativeMessages = conversationHistory.map { toNativeMessage(it) }
            val initialMessages = nativeMessages.dropLast(1)
            val lastMessage = nativeMessages.last()

            val systemContents = systemPrompt?.takeIf { it.isNotBlank() }?.let { Contents.of(it) }
            val samplerConfig = temperature?.let {
                SamplerConfig(topK = 40, topP = 0.95, temperature = it)
            }

            val conversation = try {
                val config = ConversationConfig(
                    systemInstruction = systemContents,
                    initialMessages = initialMessages,
                    tools = toolProviders,
                    samplerConfig = samplerConfig,
                    automaticToolCalling = false,
                )
                engine.createConversation(config)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                trySend(ChatStreamEvent.Error(code = "local", message = t.message ?: "Failed to create conversation", retryable = false))
                close()
                return@withLock
            }

            var conversationClosed = false
            fun closeConversationOnce() {
                if (conversationClosed) return
                conversationClosed = true
                runCatching { conversation.close() }
            }

            try {
                var seenText = ""
                var firstText = true
                val emittedToolCalls = mutableSetOf<String>()

                conversation.sendMessageAsync(lastMessage).collect { message ->
                    // 1. Process structured Tool Calls if emitted
                    for (call in message.toolCalls) {
                        val argsJson = gson.toJson(call.arguments)
                        val callKey = "${call.name}:$argsJson"
                        if (emittedToolCalls.add(callKey)) {
                            trySend(ChatStreamEvent.ToolCallRequested(name = call.name, argsJson = argsJson))
                        }
                    }

                    // 2. Process Text Deltas
                    val currentText = messageText(message)
                    if (currentText.isNotEmpty() && currentText != seenText) {
                        when {
                            firstText -> {
                                trySend(ChatStreamEvent.TokenDelta(currentText))
                                seenText = currentText
                                firstText = false
                            }
                            currentText.length > seenText.length && currentText.startsWith(seenText) -> {
                                trySend(ChatStreamEvent.TokenDelta(currentText.substring(seenText.length)))
                                seenText = currentText
                            }
                            else -> {
                                trySend(ChatStreamEvent.TokenDelta(currentText))
                                seenText = ""
                            }
                        }
                    }
                }
                closeConversationOnce()
                trySend(ChatStreamEvent.Done)
                close()
            } catch (e: CancellationException) {
                runCatching { conversation.cancelProcess() }
                closeConversationOnce()
                throw e
            } catch (t: Throwable) {
                runCatching { conversation.cancelProcess() }
                closeConversationOnce()
                trySend(ChatStreamEvent.Error(code = "local", message = t.message ?: "On-device inference failed", retryable = false))
                close()
            } finally {
                closeConversationOnce()
            }
        }
        awaitClose { }
    }

    override suspend fun generate(
        prompt: String,
        temperature: Double?,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val req = listOf(Message(conversationId = "direct", role = MessageRole.USER, content = prompt))
        streamChat(conversationHistory = req, temperature = temperature).collect { event ->
            when (event) {
                is ChatStreamEvent.TokenDelta -> onPartial(event.text)
                is ChatStreamEvent.Done -> onDone()
                is ChatStreamEvent.Error -> onError(RuntimeException(event.message))
                else -> Unit
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { engine.close() }
    }

    private fun toNativeMessage(message: Message): LiteRtMessage {
        return when (message.role) {
            MessageRole.USER -> {
                LiteRtMessage.user(message.content)
            }
            MessageRole.SYSTEM -> {
                LiteRtMessage.system(message.content)
            }
            MessageRole.ASSISTANT -> {
                val callName = message.toolCallName
                if (callName != null) {
                    val argsMap: Map<String, Any> = runCatching {
                        val jsonElem = JsonParser.parseString(message.toolCallArgsJson ?: "{}")
                        if (jsonElem.isJsonObject) {
                            jsonToMap(jsonElem.asJsonObject)
                        } else emptyMap()
                    }.getOrDefault(emptyMap())

                    val toolCall = ToolCall(
                        name = callName,
                        arguments = argsMap,
                    )
                    val textContent = if (message.content.isNotEmpty()) {
                        Contents.of(Content.Text(message.content))
                    } else {
                        Contents.of(Content.Text(""))
                    }
                    LiteRtMessage.model(
                        contents = textContent,
                        toolCalls = listOf(toolCall),
                        channels = emptyMap(),
                    )
                } else {
                    LiteRtMessage.model(message.content)
                }
            }
            MessageRole.TOOL -> {
                val toolResponse = Content.ToolResponse(
                    name = message.toolCallName ?: "tool",
                    response = message.content,
                )
                LiteRtMessage.tool(
                    Contents.of(toolResponse),
                )
            }
        }
    }

    private fun jsonToMap(json: JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for ((key, value) in json.entrySet()) {
            when {
                value.isJsonPrimitive -> {
                    val prim = value.asJsonPrimitive
                    when {
                        prim.isBoolean -> map[key] = prim.asBoolean
                        prim.isNumber -> map[key] = prim.asNumber
                        else -> map[key] = prim.asString
                    }
                }
                value.isJsonObject -> map[key] = jsonToMap(value.asJsonObject)
                value.isJsonArray -> map[key] = value.asJsonArray.toString()
                value.isJsonNull -> Unit
            }
        }
        return map
    }

    /** Concatenates the text parts of a streamed [LiteRtMessage] (role + tool calls are dropped). */
    private fun messageText(message: LiteRtMessage): String =
        buildString {
            for (content in message.contents.contents) {
                if (content is Content.Text) append(content.text)
            }
        }

    private class LiteRtLmOpenApiTool(
        private val definition: ToolDefinition,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            val root = JsonObject()
            root.addProperty("name", definition.name)
            root.addProperty("description", definition.description)
            val params = runCatching {
                JsonParser.parseString(definition.parametersSchemaJson).asJsonObject
            }.getOrNull() ?: JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
            root.add("parameters", params)
            return root.toString()
        }

        override fun execute(jsonArgs: String): String = ""
    }

    companion object {
        /**
         * Creates (and loads) the engine for [modelFile]. Blocking — call from a background thread.
         * Tries GPU first, falls back to CPU if the GPU backend fails to load the model.
         */
        fun create(
            context: Context,
            modelFile: File,
        ): LiteRtLmEngine {
            checkModelFile(modelFile)
            val cacheDir = context.cacheDir.absolutePath

            // Backend.GPU() never throws at construction — an unsupported GPU only
            // surfaces when the model is loaded, so the fallback must wrap initialize().
            return try {
                val engine = loadEngine(Backend.GPU(), modelFile, cacheDir)
                Log.i(TAG, "Loaded ${modelFile.name} on the GPU backend")
                LiteRtLmEngine(engine)
            } catch (e: CancellationException) {
                throw e
            } catch (gpuError: Throwable) {
                Log.w(TAG, "GPU backend failed (${gpuError.message}); falling back to CPU")
                try {
                    val engine = loadEngine(Backend.CPU(), modelFile, cacheDir)
                    Log.i(TAG, "Loaded ${modelFile.name} on the CPU backend")
                    LiteRtLmEngine(engine)
                } catch (e: CancellationException) {
                    throw e
                } catch (cpuError: Throwable) {
                    throw IllegalStateException(
                        "On-device model failed to load on CPU: ${cpuError.message}",
                        cpuError,
                    )
                }
            }
        }

        private fun loadEngine(
            backend: Backend,
            modelFile: File,
            cacheDir: String,
        ): Engine {
            val engine =
                Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = cacheDir,
                    ),
                )
            try {
                engine.initialize()
            } catch (t: Throwable) {
                // close() throws on an engine that never initialized — best-effort cleanup.
                runCatching { engine.close() }
                throw t
            }
            return engine
        }

        fun checkModelFile(modelFile: File) {
            require(modelFile.isFile) { "On-device model file is missing: ${modelFile.absolutePath}" }
            require(modelFile.length() >= MIN_MODEL_BYTES) {
                "On-device model file is too small (${modelFile.length()} bytes) — " +
                    "it looks like a partial download or the wrong file."
            }
            require(OnDeviceModelFormat.isLiteRtLm(modelFile)) {
                "Not a LiteRT .litertlm model bundle — re-download or re-import the file."
            }
        }

        /** Anything smaller cannot be a quantized Gemma bundle — reject before JNI. */
        const val MIN_MODEL_BYTES = 4L * 1024 * 1024
    }
}

