package com.jarvis.app.entry

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.jarvis.core.agent.AssistantGoalEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Service for running background tasks and routine goals.
 */
@AndroidEntryPoint
class BackgroundGoalService : Service() {

    @Inject
    lateinit var goalEngine: AssistantGoalEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val goalText = intent?.getStringExtra("goal_text")
        if (!goalText.isNullOrEmpty()) {
            // Background goal handling logic
        }
        return START_NOT_STICKY
    }
}
