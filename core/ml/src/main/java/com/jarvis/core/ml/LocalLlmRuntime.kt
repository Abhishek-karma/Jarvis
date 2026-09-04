package com.jarvis.core.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import com.jarvis.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Connectivity probe so routing (Auto = cloud online / local offline) is testable via mocks. */
interface LocalConnectivity {
    fun isOnline(): Boolean
}

/** Android impl of [LocalConnectivity] via ConnectivityManager. */
class AndroidLocalConnectivity @Inject constructor(
    @ApplicationContext context: Context,
) : LocalConnectivity {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isOnline(): Boolean = runCatching {
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

    @Volatile
    private var cached: Pair<LocalModelSpec, OnDeviceEngine>? = null

    init {
        scope.launch(dispatchers.io) {
            store.status.collectLatest { state ->
                if (state is LocalModelState.Ready) {
                    // Eager-load the native engine as soon as a model is installed so the first
                    // turn doesn't pay the multi-second load. currentProvider() caches the engine.
                    currentProvider()
                } else {
                    releaseEngine()
                }
            }
        }
    }

    /** Drops the cached engine (if any). Safe to call repeatedly. */
    fun releaseEngine() {
        cached?.second?.close()
        cached = null
    }

    /**
     * The local provider for the currently installed model, or null when no model is Ready
     * (or the native engine fails to load). Engine creation loads the model — seconds — so it
     * happens on the io dispatcher and the result is cached for subsequent turns.
     */
    suspend fun currentProvider(): LocalLlmProvider? {
        val state = store.status.value as? LocalModelState.Ready ?: return null
        val spec = state.model
        cached?.let { (cachedSpec, engine) ->
            if (cachedSpec == spec) return LocalLlmProvider(localProviderId(spec), spec, engine)
        }
        val engine = withContext(dispatchers.io) {
            runCatching { MediaPipeEngine.create(appContext, state.file) }.getOrNull()
        } ?: return null
        cached = spec to engine
        return LocalLlmProvider(localProviderId(spec), spec, engine)
    }

    companion object {
        fun localProviderId(spec: LocalModelSpec): String = "local-${spec.id}"
    }
}
