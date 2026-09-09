package com.jarvis.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.jarvis.core.agent.RoutineScheduler
import com.jarvis.core.agent.TaskEngine
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Hilt entry point so process-scoped startup work can reach singletons from [Application]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppStartup {
    fun routineScheduler(): RoutineScheduler

    fun taskEngine(): TaskEngine
}

@HiltAndroidApp
class JarvisApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(hiltWorkerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Process-lifetime scope: the syncs below are one-shot startup repairs.
        val startup = EntryPointAccessors.fromApplication(this, AppStartup::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            // Re-arm durable WorkManager jobs for all enabled routines (covers routines
            // created before this build shipped and any scheduler drift), and re-queue
            // tasks left RUNNING by a crash or force-stop.
            startup.routineScheduler().syncAll()
            startup.taskEngine().recoverOrphanedTasks()
        }
    }
}
