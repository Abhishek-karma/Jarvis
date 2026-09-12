package com.jarvis.core.agent

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentContinuationTrackerTest {
    private fun history() = listOf(
        Message(conversationId = "c1", role = MessageRole.USER, content = "create a txt file"),
    )

    @Test
    fun `short follow-up continues parked create_file task`() {
        val tracker = AgentContinuationTracker(nowMs = { 0L })
        tracker.park("c1", "create_file", listOf("file_name", "content"), null, history())
        val pending = tracker.consumeIfContinuation("c1", "welcome")
        assertTrue(pending != null && pending.toolName == "create_file")
        // Single-shot: second consume finds nothing pending.
        assertNull(tracker.consumeIfContinuation("c1", "welcome"))
    }

    @Test
    fun `cancel phrases abandon the parked task`() {
        val tracker = AgentContinuationTracker(nowMs = { 0L })
        tracker.park("c1", "create_file", listOf("file_name"), null, history())
        assertNull(tracker.consumeIfContinuation("c1", "never mind"))
        assertNull(tracker.peek())
    }

    @Test
    fun `new task abandons the parked task`() {
        val tracker = AgentContinuationTracker(nowMs = { 0L })
        tracker.park("c1", "create_file", listOf("file_name"), null, history())
        assertNull(tracker.consumeIfContinuation("c1", "check the battery"))
        assertNull(tracker.peek())
    }

    @Test
    fun `expired continuation is dropped`() {
        var now = 0L
        val tracker = AgentContinuationTracker(nowMs = { now }, ttlMs = 1000L)
        tracker.park("c1", "create_file", listOf("file_name"), null, history())
        now = 2000L
        assertNull(tracker.consumeIfContinuation("c1", "welcome"))
    }

    @Test
    fun `fillCreateFileArgs folds welcome into filename and content`() {
        val args = AgentContinuationTracker.fillCreateFileArgs(null, "welcome")
        assertTrue(args.contains("\"file_name\":\"welcome.txt\""))
        assertTrue(args.contains("\"content\":\"welcome\""))
        assertTrue(AgentContinuationTracker.missingCreateFileFields(args, emptyList()).isEmpty())
    }

    @Test
    fun `conversation change never leaks pending state`() {
        val tracker = AgentContinuationTracker(nowMs = { 0L })
        tracker.park("c1", "create_file", listOf("file_name"), null, history())
        assertNull(tracker.consumeIfContinuation("c2", "welcome"))
        assertNull(tracker.peek())
    }
}
