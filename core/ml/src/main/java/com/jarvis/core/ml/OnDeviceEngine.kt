package com.jarvis.core.ml


interface OnDeviceEngine : AutoCloseable {

    suspend fun generate(
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    )

    override fun close()
}
