package com.jarvis.feature.chat

import com.jarvis.core.common.ThinkMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Table tests for the [ThinkModeHeuristic]: OFF/ON short-circuits plus one case per AUTO
 * trigger (math/code, creative writing, explicit reasoning ask) and the negative space.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ThinkModeHeuristicTest {
    private fun think(
        message: String,
        mode: ThinkMode = ThinkMode.AUTO,
    ) = ThinkModeHeuristic.shouldThink(message, mode)

    @Test
    fun `OFF never thinks`() {
        assertEquals(false, think("solve this integral of x^2 step by step", ThinkMode.OFF))
        assertEquals(false, think("```kotlin\ncode\n```", ThinkMode.OFF))
    }

    @Test
    fun `ON always thinks`() {
        assertEquals(true, think("hi", ThinkMode.ON))
        assertEquals(true, think("", ThinkMode.ON))
    }

    @Test
    fun `AUTO - casual chat does not think`() {
        assertEquals(false, think("hey what's up"))
        assertEquals(false, think("thanks, that worked"))
        assertEquals(false, think(""))
    }

    @Test
    fun `AUTO - math and code trigger thinking`() {
        val mathCodeMessages =
            listOf(
                "solve x = 3y + 2 for y",
                "what is the integral of x^2",
                "sum the series Σ 1/n",
                "```kotlin\nfun main() {}\n```",
                "write a def factorial(n) in python",
                "explain this class Foo does",
            )
        mathCodeMessages.forEach { message ->
            assertEquals(true, think(message), "expected thinking for: $message")
        }
    }

    @Test
    fun `AUTO - creative-writing prompts trigger thinking`() {
        val creativeMessages =
            listOf(
                "write a poem about the sea",
                "compose a song for my dog's birthday",
                "write a short story about dragons",
                "draft a haiku about morning coffee",
                "write an essay on urban planning",
            )
        creativeMessages.forEach { message ->
            assertEquals(true, think(message), "expected thinking for: $message")
        }
    }

    @Test
    fun `AUTO - explicit reasoning asks trigger thinking`() {
        val reasoningMessages =
            listOf(
                "explain step by step how a fridge works",
                "show your work on this one",
                "reason through the options with me",
                "solve this riddle",
                "prove that the sum of two odd numbers is even",
            )
        reasoningMessages.forEach { message ->
            assertEquals(true, think(message), "expected thinking for: $message")
        }
    }

    @Test
    fun `AUTO - bare write ask without creative noun does not think`() {
        // "write a review" is transactional, not creative — must not false-positive.
        assertEquals(false, think("write a review for my landlord"))
        assertEquals(false, think("write an email to Sam"))
    }

    @Test
    fun `AUTO - long plain prose does not automatically think`() {
        // Length alone is a routing concern (RoutingClassifier), not a thinking signal.
        assertEquals(false, think("please summarize " + "this long report ".repeat(80)))
    }
}
