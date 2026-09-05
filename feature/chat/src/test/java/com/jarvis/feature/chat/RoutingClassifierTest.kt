package com.jarvis.feature.chat

import com.jarvis.core.common.RoutingOverride
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Table-driven tests for the [RoutingClassifier] decision tree — one case per branch of
 * the 7-step tree plus the heuristic false-positive guards and tie-breaker edges.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RoutingClassifierTest {
    private fun classify(
        message: String = "hello",
        override: RoutingOverride = RoutingOverride.AUTO,
        localModelReady: Boolean = false,
        isOnline: Boolean = true,
    ) = RoutingClassifier.classify(message, override, localModelReady, isOnline)

    @Test
    fun `step 1 - LOCAL pinned with a ready model routes on-device`() {
        assertEquals(
            RoutingDecision(RoutingOverride.LOCAL, RoutingReason.FORCED_LOCAL),
            classify(override = RoutingOverride.LOCAL, localModelReady = true),
        )
    }

    @Test
    fun `step 1 - LOCAL pinned without a ready model falls back to cloud`() {
        val decision =
            classify(
                message = "what's the weather today",
                override = RoutingOverride.LOCAL,
                localModelReady = false,
            )
        // Fallback wins even over the real-time heuristic.
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.FORCED_LOCAL_FALLBACK),
            decision,
        )
    }

    @Test
    fun `step 2 - CLOUD pinned routes cloud regardless of the heuristics`() {
        val decision =
            classify(
                message = "my password is hunter2 — keep it private",
                override = RoutingOverride.CLOUD,
                localModelReady = true,
            )
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.FORCED_CLOUD),
            decision,
        )
    }

    @Test
    fun `step 3 - AUTO privacy content routes local when the model is ready`() {
        assertEquals(
            RoutingDecision(RoutingOverride.LOCAL, RoutingReason.PRIVACY_LOCAL),
            classify(message = "where did I put my password", localModelReady = true),
        )
    }

    @Test
    fun `step 3 - privacy heuristic needs a ready local model`() {
        // Without the model there is no local option — cloud default (step 7).
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.DEFAULT_CLOUD),
            classify(message = "my ssn is 123-45-6789", localModelReady = false),
        )
    }

    @Test
    fun `step 4 - AUTO realtime questions route cloud even with a local model`() {
        val realtimeMessages =
            listOf(
                "what's the weather",
                "what is the current temperature",
                "any news today?",
                "what's the stock price of AAPL",
                "what time is it right now",
            )
        realtimeMessages.forEach { message ->
            assertEquals(
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.REALTIME_CLOUD),
                classify(message = message, localModelReady = true),
                "expected CLOUD for: $message",
            )
        }
    }

    @Test
    fun `step 5 - AUTO heavy-generative asks route cloud`() {
        val heavyMessages =
            listOf(
                "write a 2000-word essay on Rome",
                "compose a long story about dragons",
                "draft a detailed report on quarterly sales",
            )
        heavyMessages.forEach { message ->
            assertEquals(
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.HEAVY_GENERATIVE_CLOUD),
                classify(message = message, localModelReady = true),
                "expected CLOUD for: $message",
            )
        }
    }

    @Test
    fun `step 5b - AUTO long free-form text routes cloud by length`() {
        val longMessage = "x".repeat(1_001)
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.HEAVY_GENERATIVE_CLOUD),
            classify(message = longMessage, localModelReady = true),
        )
    }

    @Test
    fun `step 6 - AUTO light message with a ready model routes local`() {
        assertEquals(
            RoutingDecision(RoutingOverride.LOCAL, RoutingReason.LIGHT_LOCAL),
            classify(message = "hey, what's up?", localModelReady = true),
        )
    }

    @Test
    fun `step 6b - AUTO light message at exactly the length threshold stays local`() {
        assertEquals(
            RoutingDecision(RoutingOverride.LOCAL, RoutingReason.LIGHT_LOCAL),
            classify(message = "x".repeat(1_000), localModelReady = true),
        )
    }

    @Test
    fun `step 7 - AUTO without a ready model defaults to cloud`() {
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.DEFAULT_CLOUD),
            classify(message = "hello", localModelReady = false),
        )
    }

    @Test
    fun `empty message skips the text heuristics`() {
        // No text to inspect (conversation-open re-resolve): ready model → light local.
        assertEquals(
            RoutingDecision(RoutingOverride.LOCAL, RoutingReason.LIGHT_LOCAL),
            classify(message = "", localModelReady = true),
        )
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.DEFAULT_CLOUD),
            classify(message = "", localModelReady = false),
        )
    }

    @Test
    fun `offline does not force local on its own`() {
        // Connectivity informs the old 2x2 matrix; the tree only needs local-model readiness.
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.DEFAULT_CLOUD),
            classify(message = "hello", localModelReady = false, isOnline = false),
        )
    }

    @Test
    fun `privacy heuristic avoids false positives on benign directive-style messages`() {
        // "make this conversation private" is a directive, not a data disclosure — the
        // allowlisted-phrase patterns ("my password", "my SSN", …) must not fire on it.
        listOf(
            "make this conversation private",
            "how do I set up a vpn for privacy",
            "write a poem about privacy",
        ).forEach { message ->
            assertEquals(
                RoutingDecision(RoutingOverride.LOCAL, RoutingReason.LIGHT_LOCAL),
                classify(message = message, localModelReady = true),
                "expected LOCAL for: $message",
            )
        }
    }

    @Test
    fun `privacy outranks realtime and heaviness`() {
        // A message matching multiple AUTO heuristics must hit the highest-precedence one.
        assertEquals(
            RoutingDecision(RoutingOverride.LOCAL, RoutingReason.PRIVACY_LOCAL),
            classify(
                message = "summarize the private data I shared today",
                localModelReady = true,
            ),
        )
    }

    @Test
    fun `realtime outranks heaviness`() {
        assertEquals(
            RoutingDecision(RoutingOverride.CLOUD, RoutingReason.REALTIME_CLOUD),
            classify(
                message = "write a long detailed essay about today's weather",
                localModelReady = true,
            ),
        )
    }
}
