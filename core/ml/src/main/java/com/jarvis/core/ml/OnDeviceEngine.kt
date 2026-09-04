package com.jarvis.core.ml

import android.content.Context
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Minimal on-device generation contract. Every runtime (MediaPipe v1, LiteRT-LM later) adapts
 * into this, so the rest of the app never touches a native API.
 */
interface OnDeviceEngine : AutoCloseable {
    /**
     * Runs one generation to completion. [onPartial] receives incremental token deltas,
     * [onDone] fires once, [onError] on failure. Suspends until the response finishes; callers
     * cancel by cancelling the coroutine (implementations abort the native call when possible).
     */
    suspend fun generate(
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    )

    override fun close()
}

/**
 * MediaPipe LLM Inference engine (tasks-genai 0.10.x). One [LlmInference] instance per model
 * file is created — model load takes seconds — so callers reuse the instance across turns:
 * [LocalLlmRuntime] caches it and rebuilds only when the model file changes.
 *
 * The 0.10.27 API streams via `generateResponseAsync(prompt, ProgressListener)` returning a
 * ListenableFuture; cancelling the collecting coroutine cancels the native generation.
 */
class MediaPipeEngine private constructor(
    private val inference: LlmInference,
) : OnDeviceEngine {

    private val mutex = Mutex()

    override suspend fun generate(
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        mutex.withLock {
            val completed = CompletableDeferred<Unit>()
            var last = ""
            lateinit var future: ListenableFuture<String>
            try {
                future = inference.generateResponseAsync(prompt) { partial, done ->
                    if (partial.length > last.length) {
                        onPartial(partial.substring(last.length))
                        last = partial
                    }
                    if (done) completed.complete(Unit)
                }
            } catch (t: Throwable) {
                onError(t)
                return@withLock
            }
            // Safety net: if the native side finishes without a done=true progress callback
            // (rare error path), resume anyway rather than hang the engine mutex.
            future.addListener({ completed.complete(Unit) }, MoreExecutors.directExecutor())
            try {
                completed.await()
                onDone()
            } catch (t: kotlinx.coroutines.CancellationException) {
                future.cancel(true)
                throw t
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    override fun close() {
        runCatching { inference.close() }
    }

    companion object {
        /** Creates (and loads) the engine for [modelFile]. Blocking — call from a background thread. */
        fun create(context: Context, modelFile: File, maxTokens: Int = DEFAULT_MAX_TOKENS): MediaPipeEngine {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(40)
                .build()
            return MediaPipeEngine(LlmInference.createFromOptions(context, options))
        }

        // 2048 gives agent turns room for a <tool_call> plus reasoning in one generation.
        private const val DEFAULT_MAX_TOKENS = 2048
    }
}
