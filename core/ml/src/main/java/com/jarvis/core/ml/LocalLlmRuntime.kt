package com.jarvis.core.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Connectivity probe so routing (Auto = cloud online / local offline) is testable via mocks. */
interface LocalConnectivity {
    fun isOnline(): Boolean
}

/** Android impl of [LocalConnectivity] via ConnectivityManager. */
class AndroidLocalConnectivity
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : LocalConnectivity {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        override fun isOnline(): Boolean =
            runCatching {
                val network = connectivityManager.activeNetwork ?: return@runCatching false
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.getOrDefault(false)
    }

/**
 * Builds a [LocalLlmProvider] for the installed model, caching the (expensive) native engine
 * instance and tearing it down the moment the store leaves Ready (delete / failed refresh).
 */
class LocalLlmRuntime(
    private val appContext: Context,
    private val store: LocalModelStore,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Guard for [cached]: engine load takes seconds, so check-then-act must be atomic. */
    private val cacheMutex = Mutex()

    /**
     * Serializes check-load-publish so prewarm and a user send can't create two native
     * engines for the same model concurrently. Each load holds the whole quantized model
     * in RAM; two at once on an 8 GB device is an LMK-kill/freeze risk mid-turn.
     */
    private val loadMutex = Mutex()
    private var cached: Pair<LocalModelSpec, OnDeviceEngine>? = null

    /**
     * Why the last [currentProvider] attempt came back null, for an honest error message.
     * Set on every failure path; null means "no model installed" (callers use their own copy).
     */
    @Volatile
    var lastFailure: String? = null

    init {
        scope.launch {
            store.status.collectLatest { state ->
                if (state is LocalModelState.Ready) {
                    // Eager-load the native engine as soon as a model is installed so the first
                    // turn doesn't pay the multi-second load. currentProvider() caches the engine.
                    // A failed prewarm is non-fatal (surfaced per-turn); only cancellation
                    // propagates so collectLatest keeps working.
                    try {
                        prewarm(state)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Swallowed: load failure surfaces when the user actually sends.
                    }
                } else {
                    releaseEngine()
                }
            }
        }
    }

    /**
     * Drops the cached engine (if any). Safe to call repeatedly and from any thread.
     * The engine's own close is deferred while a generation is in flight, so releasing
     * mid-turn never invalidates the native handle under it.
     */
    suspend fun releaseEngine() {
        val old =
            cacheMutex.withLock {
                val previous = cached
                cached = null
                previous
            }
        // Close outside the lock: the engine defers the native close while busy,
        // so this never blocks a turn for longer than a lock handoff.
        old?.second?.close()
    }

    /**
     * Eagerly loads (and caches) the engine for an already-Ready [state] without
     * building a provider. No-op when the cached engine already matches.
     */
    suspend fun prewarm(state: LocalModelState.Ready) {
        if (!state.file.isFile || state.file.length() <= 0) return
        cacheMutex.withLock {
            val (cachedSpec, _) = cached ?: (null to null)
            if (cachedSpec == state.model) return
        }
        currentProvider()
    }

    /**
     * The local provider for the currently installed model, or null when no model is Ready
     * (or the native engine fails to load). Engine creation loads the model — seconds — so it
     * happens on the io dispatcher and the result is cached for subsequent turns.
     *
     * Check-load-publish runs under [loadMutex]: a concurrent prewarm and first send serialize
     * instead of loading two full-size native engines into RAM at once.
     *
     * Cancellation is never swallowed: a cancelled load rethrows so structured
     * concurrency (collectLatest prewarm, a torn-down send) actually stops the work,
     * and the freshly created engine is closed rather than leaked.
     */
    suspend fun currentProvider(): LocalLlmProvider? {
        val state = store.status.value as? LocalModelState.Ready ?: return null
        val spec = state.model
        return loadMutex.withLock load@{
            // Fast path: already loaded for this spec.
            var hit: LocalLlmProvider? = null
            cacheMutex.withLock {
                cached?.let { (cachedSpec, engine) ->
                    if (cachedSpec == spec) {
                        hit = LocalLlmProvider(localProviderId(spec), spec, engine)
                    }
                }
            }
            hit?.let { return@load it }

            // The installed model is a .litertlm container → LiteRT-LM. (MediaPipe .task
            // bundles were removed from this build: they need native libs that aren't
            // present on every ABI and abort the process instead of throwing.)
            val engine =
                try {
                    withContext(dispatchers.io) {
                        LiteRtLmEngine.create(appContext, state.file)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastFailure = t.message?.takeIf { it.isNotBlank() }
                        ?: "On-device model failed to load."
                    return@load null
                }
            // Publish atomically. A cancellation between create and publish (e.g. the model
            // was deleted mid-load) must close the native handle, never leak it.
            val active =
                try {
                    cacheMutex.withLock {
                        val previous = cached
                        when {
                            previous == null -> {
                                cached = spec to engine
                                spec to engine
                            }
                            previous.first == spec -> {
                                // A release raced us and reloaded the same spec — drop ours.
                                engine.close()
                                previous
                            }
                            else -> {
                                val old = previous.second
                                cached = spec to engine
                                old.close()
                                spec to engine
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    engine.close()
                    throw e
                }
            LocalLlmProvider(localProviderId(active.first), active.first, active.second)
        }
    }

    companion object {
        fun localProviderId(spec: LocalModelSpec): String = "local-${spec.id}"
    }
}
