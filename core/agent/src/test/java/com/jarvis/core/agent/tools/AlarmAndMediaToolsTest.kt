package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** JVM tests for the alarm and volume tools — platform senders are plain lambdas. */
class AlarmAndMediaToolsTest {
    @Test
    fun `both tools are reversible-write tier`() {
        assertEquals(PermissionTier.REVERSIBLE_WRITE, AlarmTools.setAlarm { _, _ -> Result.success(Unit) }.tier)
        assertEquals(PermissionTier.REVERSIBLE_WRITE, MediaTools.adjustVolume { _, _ -> Result.success("ok") }.tier)
    }

    @Test
    fun `set_alarm requires a future timestamp`() =
        runBlocking {
            val tool = AlarmTools.setAlarm { _, _ -> Result.success(Unit) }

            val missing = tool.execute("""{"label":"wake"}""")
            assertFalse(missing.success)
            assertTrue(missing.error!!.contains("at_utc_millis"))

            val past = tool.execute("""{"at_utc_millis": ${System.currentTimeMillis() - 1000}}""")
            assertFalse(past.success)
            assertTrue(past.error!!.contains("future"))
        }

    @Test
    fun `set_alarm delivers the timestamp and truncates the label`() =
        runBlocking {
            val at = System.currentTimeMillis() + 3_600_000
            val seen = mutableListOf<Pair<Long, String>>()
            val tool =
                AlarmTools.setAlarm { triggerAt, label ->
                    seen += triggerAt to label
                    Result.success(Unit)
                }

            val result = tool.execute("""{"at_utc_millis":$at,"label":"${"L".repeat(80)}"}""")

            assertTrue(result.success)
            assertEquals(at, seen.single().first)
            assertEquals(AlarmTools.MAX_LABEL_CHARS, seen.single().second.length)
        }

    @Test
    fun `set_alarm defaults a blank label`() =
        runBlocking {
            val seen = mutableListOf<Pair<Long, String>>()
            val tool =
                AlarmTools.setAlarm { triggerAt, label ->
                    seen += triggerAt to label
                    Result.success(Unit)
                }

            tool.execute("""{"at_utc_millis":${System.currentTimeMillis() + 600_000}}""")

            assertEquals("Jarvis alarm", seen.single().second)
        }

    @Test
    fun `adjust_volume validates action and stream`() =
        runBlocking {
            val tool = MediaTools.adjustVolume { _, _ -> Result.success("ok") }

            val badAction = tool.execute("""{"action":"louder"}""")
            assertFalse(badAction.success)
            assertTrue(badAction.error!!.contains("action"))

            val badStream = tool.execute("""{"action":"up","stream":"music"}""")
            assertFalse(badStream.success)
            assertTrue(badStream.error!!.contains("stream"))
        }

    @Test
    fun `adjust_volume defaults the stream to media`() =
        runBlocking {
            val seen = mutableListOf<Pair<String, String>>()
            val tool =
                MediaTools.adjustVolume { action, stream ->
                    seen += action to stream
                    Result.success("media volume 8/15")
                }

            val result = tool.execute("""{"action":"up"}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("media volume 8/15"))
            assertEquals("up" to "media", seen.single())
        }
}
