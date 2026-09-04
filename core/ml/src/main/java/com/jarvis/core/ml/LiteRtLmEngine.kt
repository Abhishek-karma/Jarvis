package com.jarvis.core.ml

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiteRT-LM engine (litertlm-android 0.15.x). Loads `.litertlm` containers — the newer
 * LiteRT conversation API (`Engine` + `Conversation`) with GPU/NPU acceleration options.
 *
 * The app builds stateless full prompts per turn ([LocalPromptBuilder]), so a fresh
 * conversation is created for every generation instead of letting the native runtime
 * accumulate chat state. The [Engine] instance itself is cached by [LocalLlmRuntime]
 * across turns; only [close] tears it down.
 *
 * Streaming: `sendMessageAsync` emits [com.google.ai.edge.litertlm.Message] objects as the
 * response arrives (either cumulative-so-far or delta chunks depending on the runtime);
 * both shapes are normalized into [OnDeviceEngine.generate] deltas.
 */
class LiteRtLmEngine private constructor(
    private val engine: Engine,
) : OnDeviceEngine {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun generate(
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (closed.get()) {
            onError(IllegalStateException("On-device engine is closed"))
            return
        }
        mutex.withLock {
            if (closed.get()) {
                onError(IllegalStateException("On-device engine is closed"))
                return@withLock
            }
            val conversation =
                try {
                    engine.createConversation()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    onError(t)
                    return@withLock
                }
            try {
                conversation.use { conv ->
                    var seen = ""
                    var first = true
                    conv.sendMessageAsync(prompt).collect { message ->
                        val text = messageText(message)
                        if (text.isEmpty() || text == seen) return@collect
                        when {
                            first -> {
                                onPartial(text)
                                seen = text
                                first = false
                            }
                            // Cumulative streaming: each emission is the full text so far.
                            text.length > seen.length && text.startsWith(seen) -> {
                                onPartial(text.substring(seen.length))
                                seen = text
                            }
                            // Delta streaming: each emission is the next chunk.
                            else -> {
                                onPartial(text)
                                seen = ""
                            }
                        }
                    }
                    onDone()
                }
            } catch (e: CancellationException) {
                // Abort the in-flight native generation so the next turn doesn't collide with it.
                runCatching { conversation.cancelProcess() }
                throw e
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { engine.close() }
    }

    /** Concatenates the text parts of a streamed [Message] (role + tool calls are dropped). */
    private fun messageText(message: Message): String =
        buildString {
            for (content in message.contents.contents) {
                if (content is Content.Text) append(content.text)
            }
        }

    companion object {
        /** Creates (and loads) the engine for [modelFile]. Blocking — call from a background thread. */
        fun create(
            context: Context,
            modelFile: File,
        ): LiteRtLmEngine {
            checkModelFile(modelFile)
            val config =
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    // CPU backend is universally available; GPU/NPU need extra native libs.
                    backend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath,
                )
            val engine = Engine(config)
            try {
                engine.initialize()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                runCatching { engine.close() }
                throw IllegalStateException("On-device model failed to load: ${t.message}", t)
            }
            return LiteRtLmEngine(engine)
        }

        /**
         * Fails fast on missing/empty/corrupt files before they reach JNI, where a bad
         * bundle can abort the process instead of throwing.
         */
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
