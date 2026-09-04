package com.jarvis.core.ml

/**
 * Minimal on-device generation contract. The LiteRT-LM runtime ([LiteRtLmEngine]) adapts
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
