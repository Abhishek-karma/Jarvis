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


class LocalLlmRuntime(
    private val appContext: Context,
    private val store: LocalModelStore,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Guard for [cached]: engine load takes seconds, so check-then-act must be atomic. */
    private val cacheMutex = Mutex()


    private val loadMutex = Mutex()
    private var cached: Pair<LocalModelSpec, OnDeviceEngine>? = null


    @Volatile
    var lastFailure: String? = null

    init {
        scope.launch {
            store.status.collectLatest { state ->
                if (state is LocalModelState.Ready) {




                    try {
                        prewarm(state)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {

                    }
                } else {
                    releaseEngine()
                }
            }
        }
    }


    suspend fun releaseEngine() {
        val old =
            cacheMutex.withLock {
                val previous = cached
                cached = null
                previous
            }


        old?.second?.close()
    }


    suspend fun prewarm(state: LocalModelState.Ready) {
        if (!state.file.isFile || state.file.length() <= 0) return
        cacheMutex.withLock {
            val (cachedSpec, _) = cached ?: (null to null)
            if (cachedSpec == state.model) return
        }
        currentProvider()
    }


    suspend fun currentProvider(): LocalLlmProvider? {
        val state = store.status.value as? LocalModelState.Ready ?: return null
        val spec = state.model
        return loadMutex.withLock load@{

            var hit: LocalLlmProvider? = null
            cacheMutex.withLock {
                cached?.let { (cachedSpec, engine) ->
                    if (cachedSpec == spec) {
                        hit = LocalLlmProvider(localProviderId(spec), spec, engine)
                    }
                }
            }
            hit?.let { return@load it }




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
