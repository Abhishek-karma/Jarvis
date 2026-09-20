package com.jarvis.app.entry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.jarvis.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Handles text selection context menu intent (android.intent.action.PROCESS_TEXT).
 * Extracts selected text and forwards to MainActivity/GoalEngine as a goal.
 */
@AndroidEntryPoint
class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNull_orEmpty()) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("goal_text", text)
                putExtra("goal_source", "process_text")
            }
            startActivity(mainIntent)
        }
        finish()
    }
}
private fun String?.isNull_orEmpty(): Boolean = this == null || this.isEmpty()
