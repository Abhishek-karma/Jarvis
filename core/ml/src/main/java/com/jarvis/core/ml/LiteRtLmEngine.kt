package com.jarvis.core.ml

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.ToolDefinition
import com.jarvis.core.network.ToolResponsePayload
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
            val nativeMessages = conversationHistory.map { LiteRtMessageCodec.toNativeMessage(it) }
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
                Log.d(TAG, "Created one-shot LiteRT conversation (tools: ${toolProviders.size}, initialMsgs: ${initialMessages.size})")
                engine.createConversation(config)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create conversation", t)
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
                Log.d(TAG, "LiteRtLmEngine streamChat: sending lastMessage")
                var seenText = ""
                var firstText = true
                val emittedToolCalls = mutableSetOf<String>()

                conversation.sendMessageAsync(lastMessage).collect { message ->
                    // Native structured tool calls from LiteRT-LM
                    for (call in message.toolCalls) {
                        val argsJson = gson.toJson(call.arguments)
                        val callKey = "${call.name}:$argsJson"
                        if (emittedToolCalls.add(callKey)) {
                            Log.d(TAG, "LiteRtLmEngine native tool call: ${call.name}")
                            trySend(
                                ChatStreamEvent.ToolCallRequested(
                                    name = call.name,
                                    argsJson = argsJson,
                                ),
                            )
                        }
                    }

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
                Log.e(TAG, "LiteRtLmEngine inference error", t)
                trySend(ChatStreamEvent.Error(code = "local", message = t.message ?: "On-device inference failed", retryable = false))
                close()
            } finally {
                closeConversationOnce()
            }
        }
        awaitClose { }
    }

    override fun startSession(
        conversationHistory: List<Message>,
        systemPrompt: String?,
        tools: List<ToolDefinition>?,
        temperature: Double?,
    ): OnDeviceSession? {
        if (closed.get()) return null
        val toolProviders = tools?.map { tool(LiteRtLmOpenApiTool(it)) }.orEmpty()
        val systemContents = systemPrompt?.takeIf { it.isNotBlank() }?.let { Contents.of(it) }
        val samplerConfig = temperature?.let {
            SamplerConfig(topK = 40, topP = 0.95, temperature = it)
        }

        val nativeMessages = conversationHistory.map { LiteRtMessageCodec.toNativeMessage(it) }
        val initialMessages = if (nativeMessages.isNotEmpty() && conversationHistory.last().role == MessageRole.USER) {
            nativeMessages.dropLast(1)
        } else {
            nativeMessages
        }
        val initialUserMessage = if (conversationHistory.isNotEmpty() && conversationHistory.last().role == MessageRole.USER) {
            nativeMessages.last()
        } else null

        val conversation = try {
            val config = ConversationConfig(
                systemInstruction = systemContents,
                initialMessages = initialMessages,
                tools = toolProviders,
                samplerConfig = samplerConfig,
                automaticToolCalling = false,
            )
            Log.i(TAG, "startSession: created native LiteRT conversation session (tools=${toolProviders.size}, initialMsgs=${initialMessages.size})")
            engine.createConversation(config)
        } catch (t: Throwable) {
            Log.e(TAG, "startSession: failed to create native LiteRT conversation", t)
            return null
        }

        return object : OnDeviceSession {
            private val sessionClosed = AtomicBoolean(false)

            override fun sendInitial(): Flow<ChatStreamEvent> = callbackFlow {
                if (sessionClosed.get() || closed.get()) {
                    trySend(ChatStreamEvent.Error(code = "local", message = "Session is closed", retryable = false))
                    close()
                    return@callbackFlow
                }
                if (initialUserMessage == null) {
                    trySend(ChatStreamEvent.Done)
                    close()
                    return@callbackFlow
                }

                mutex.withLock {
                    if (sessionClosed.get() || closed.get()) {
                        trySend(ChatStreamEvent.Error(code = "local", message = "Session is closed", retryable = false))
                        close()
                        return@withLock
                    }
                    Log.i(TAG, "sendInitial: sending initial user message to native conversation")
                    collectStream(conversation, initialUserMessage)
                }
                awaitClose { }
            }

            override fun sendToolResponses(responses: List<ToolResponsePayload>): Flow<ChatStreamEvent> = callbackFlow {
                if (sessionClosed.get() || closed.get()) {
                    trySend(ChatStreamEvent.Error(code = "local", message = "Session is closed", retryable = false))
                    close()
                    return@callbackFlow
                }
                if (responses.isEmpty()) {
                    trySend(ChatStreamEvent.Done)
                    close()
                    return@callbackFlow
                }

                mutex.withLock {
                    if (sessionClosed.get() || closed.get()) {
                        trySend(ChatStreamEvent.Error(code = "local", message = "Session is closed", retryable = false))
                        close()
                        return@withLock
                    }
                    Log.i(TAG, "sendToolResponses: sending ${responses.size} tool responses to SAME native conversation: ${responses.joinToString { it.toolName }}")
                    val toolContents = responses.map {
                        Content.ToolResponse(name = it.toolName, response = it.observation)
                    }
                    val toolMsg = LiteRtMessage.tool(Contents.of(toolContents))
                    collectStream(conversation, toolMsg)
                }
                awaitClose { }
            }

            override fun close() {
                if (sessionClosed.compareAndSet(false, true)) {
                    Log.i(TAG, "close: releasing native LiteRT conversation session")
                    runCatching { conversation.close() }
                }
            }

            private suspend fun kotlinx.coroutines.channels.ProducerScope<ChatStreamEvent>.collectStream(
                conv: com.google.ai.edge.litertlm.Conversation,
                msg: LiteRtMessage,
            ) {
                try {
                    var seenText = ""
                    var firstText = true
                    val emittedToolCalls = mutableSetOf<String>()

                    conv.sendMessageAsync(msg).collect { message ->
                        for (call in message.toolCalls) {
                            val argsJson = gson.toJson(call.arguments)
                            val callKey = "${call.name}:$argsJson"
                            if (emittedToolCalls.add(callKey)) {
                                Log.i(TAG, "Session native tool call emitted: ${call.name}, args length=${argsJson.length}")
                                trySend(
                                    ChatStreamEvent.ToolCallRequested(
                                        name = call.name,
                                        argsJson = argsJson,
                                    ),
                                )
                            }
                        }

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
                    trySend(ChatStreamEvent.Done)
                    close()
                } catch (e: CancellationException) {
                    runCatching { conv.cancelProcess() }
                    throw e
                } catch (t: Throwable) {
                    runCatching { conv.cancelProcess() }
                    Log.e(TAG, "Session inference error", t)
                    trySend(ChatStreamEvent.Error(code = "local", message = t.message ?: "On-device inference failed", retryable = false))
                    close()
                }
            }
        }
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
        private val descriptionJsonString: String by lazy {
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
            root.toString()
        }

        override fun getToolDescriptionJsonString(): String = descriptionJsonString

        override fun execute(jsonArgs: String): String = ""
    }

    companion object {
        init {
            runCatching {
                System.loadLibrary("litertlm_jni")
            }
        }

        /**
         * Creates (and loads) the engine for [modelFile]. Blocking — call from a background thread.
         * On emulators or virtualized environments, CPU is selected directly to prevent Mesa/Dawn
         * buffer binding overflow errors (since software Vulkan restricts maxStorageBufferBindingSize to 128MB).
         * On physical devices, tries GPU first, falling back to CPU if the GPU backend fails.
         */
        fun create(
            context: Context,
            modelFile: File,
        ): LiteRtLmEngine {
            checkModelFile(modelFile)
            val cacheDir = context.cacheDir.absolutePath

            if (isRunningOnEmulator()) {
                Log.i(TAG, "Running on emulator environment; selecting CPU backend directly to avoid Mesa/Dawn storage buffer limit.")
                val engine = loadEngine(createCpuBackend(), modelFile, cacheDir)
                Log.i(TAG, "Loaded ${modelFile.name} on the CPU backend")
                return LiteRtLmEngine(engine)
            }

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
                    val engine = loadEngine(createCpuBackend(), modelFile, cacheDir)
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

        fun createCpuBackend(): Backend.CPU {
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = (cores - 1).coerceIn(2, 4)
            return Backend.CPU(threads, threads)
        }

        fun isRunningOnEmulator(): Boolean {
            val fingerprint = Build.FINGERPRINT.lowercase()
            val model = Build.MODEL.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val hardware = Build.HARDWARE.lowercase()
            val product = Build.PRODUCT.lowercase()
            val board = Build.BOARD.lowercase()
            val device = Build.DEVICE.lowercase()
            val brand = Build.BRAND.lowercase()

            return fingerprint.startsWith("generic") ||
                fingerprint.startsWith("unknown") ||
                model.contains("google_sdk") ||
                model.contains("emulator") ||
                model.contains("android sdk built for") ||
                manufacturer.contains("genymotion") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                hardware.contains("cutf") ||
                hardware.contains("cuttlefish") ||
                product.contains("sdk_gphone") ||
                product.contains("google_sdk") ||
                product.contains("sdk") ||
                product.contains("sdk_x86") ||
                product.contains("vbox86p") ||
                product.contains("emulator") ||
                product.contains("simulator") ||
                board.contains("goldfish") ||
                (brand.startsWith("generic") && device.startsWith("generic"))
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

