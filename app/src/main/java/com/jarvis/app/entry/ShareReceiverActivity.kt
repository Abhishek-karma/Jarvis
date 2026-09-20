package com.jarvis.app.entry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.jarvis.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Handles share sheet intent (android.intent.action.SEND).
 * Forwards shared text or content URI to MainActivity/GoalEngine.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toString()

        if (!sharedText.isNullOrEmpty()) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("goal_text", sharedText)
                putExtra("goal_source", "share")
            }
            startActivity(mainIntent)
        }
        finish()
    }
}
