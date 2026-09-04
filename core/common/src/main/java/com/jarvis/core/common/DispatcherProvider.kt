package com.jarvis.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central dispatchers — injected so tests can swap in test dispatchers and the LLM inference
 * pool can be pinned independently of Dispatchers.Default.
 */
@Singleton
class DispatcherProvider
    @Inject
    constructor() {
        val main: CoroutineDispatcher get() = Dispatchers.Main
        val io: CoroutineDispatcher get() = Dispatchers.IO
        val default: CoroutineDispatcher get() = Dispatchers.Default

        /** Dedicated 4-thread pool for local LLM inference; shares Default until then. */
        val inference: CoroutineDispatcher get() = Dispatchers.Default

        /** Voice pipeline runs on a single-thread executor, not a coroutine dispatcher. */
        fun voiceScope(): CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
    }
