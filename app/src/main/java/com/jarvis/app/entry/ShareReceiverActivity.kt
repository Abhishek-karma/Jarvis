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
        val sharedUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)

        if (!sharedText.isNullOrEmpty() || sharedUri != null) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("goal_text", sharedText ?: "Help me with the shared attachment.")
                putExtra("goal_source", "share")
                putExtra("goal_attachment_uri", sharedUri?.toString())
            }
            startActivity(mainIntent)
        }
        finish()
    }
}
