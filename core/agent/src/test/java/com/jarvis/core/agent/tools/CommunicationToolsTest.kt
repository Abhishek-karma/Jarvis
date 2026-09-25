package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** JVM tests for the communication tools — senders are plain lambdas. */
class CommunicationToolsTest {
    @Test
    fun `both tools are sensitive tier`() {
        assertEquals(PermissionTier.SENSITIVE, CommunicationTools.sendSms { _, _ -> Result.success(Unit) }.tier)
        assertEquals(PermissionTier.SENSITIVE, CommunicationTools.placeCall { Result.success(Unit) }.tier)
    }

    @Test
    fun `send_sms delivers the body`() =
        runBlocking {
            val sent = mutableListOf<Pair<String, String>>()
            val tool =
                CommunicationTools.sendSms { to, body ->
                    sent += to to body
                    Result.success(Unit)
                }

            val result = tool.execute("""{"to":"+15550100","body":"On my way"}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("+15550100"))
            assertEquals("+15550100" to "On my way", sent.single())
        }

    @Test
    fun `send_sms rejects an oversized body`() =
        runBlocking {
            val tool = CommunicationTools.sendSms { _, _ -> Result.success(Unit) }

            val result = tool.execute("""{"to":"+1","body":"${"x".repeat(1700)}"}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("long"))
        }

    @Test
    fun `send_sms failure surfaces the platform error`() =
        runBlocking {
            val tool = CommunicationTools.sendSms { _, _ -> Result.failure(IllegalStateException("radio off")) }

            val result = tool.execute("""{"to":"+1","body":"hi"}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("radio off"))
        }

    @Test
    fun `place_call dials the number`() =
        runBlocking {
            val dialed = mutableListOf<String>()
            val tool =
                CommunicationTools.placeCall { number ->
                    dialed += number
                    Result.success(Unit)
                }

            val result = tool.execute("""{"number":"+15550100"}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("Dialing"))
            assertEquals("+15550100", dialed.single())
        }

    @Test
    fun `place_call requires the number`() =
        runBlocking {
            val tool = CommunicationTools.placeCall { Result.success(Unit) }

            val result = tool.execute("""{}""")

            assertFalse(result.success)
            assertTrue(result.observationText.contains("required"))
        }

    @Test
    fun `malformed JSON fails cleanly on both tools`() =
        runBlocking {
            val sms = CommunicationTools.sendSms { _, _ -> Result.success(Unit) }
            val call = CommunicationTools.placeCall { Result.success(Unit) }

            assertFalse(sms.execute("{{{").success)
            assertFalse(call.execute("[1,2]").success)
        }
}
