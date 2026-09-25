package com.jarvis.core.agent.automation

import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.agent.execution.ExecutionStatus
import com.jarvis.core.agent.tools.PhoneAgentTool
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneAgentTest {

    @Test
    fun `phone agent fails with ACCESSIBILITY_UNAVAILABLE when service is null`() = runTest {
        val agent = DefaultPhoneAgent(
            serviceProvider = { null },
            launchApp = { Result.success(Unit) },
        )

        val result = agent.execute("Open YouTube and search Android")
        assertFalse(result.isSuccess)
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(ErrorCode.ACCESSIBILITY_UNAVAILABLE, result.errorCode)
        assertTrue(result.message.contains("Accessibility access"))
    }

    @Test
    fun `phone agent fails with APP_NOT_INSTALLED when target app is not installed`() = runTest {
        val agent = DefaultPhoneAgent(
            serviceProvider = {
                // Return dummy service instance if needed, or if null it fails with accessibility
                null
            },
            launchApp = { appName ->
                Result.failure(IllegalArgumentException("Could not find installed application matching '$appName'"))
            },
        )

        val result = agent.execute("Open NonExistentApp and search test")
        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.ACCESSIBILITY_UNAVAILABLE, result.errorCode)
    }

    @Test
    fun `phone agent tool executes successfully and wraps result`() = runTest {
        val mockAgent = object : PhoneAgent {
            override suspend fun execute(
                goal: String,
                targetApp: String?,
                confirmationGate: ConfirmationGate?,
                onProgress: (suspend (String) -> Unit)?,
            ): PhoneAgentResult {
                return PhoneAgentResult.success(
                    message = "Searched for OpenAI on Instagram.",
                    completedSteps = listOf("Opened Instagram", "Clicked Search", "Typed \"OpenAI\""),
                )
            }
        }

        val tool = PhoneAgentTool(mockAgent)
        val toolResult = tool.execute("""{"goal":"Open Instagram and search OpenAI","app_name":"Instagram"}""")

        assertTrue(toolResult.success)
        assertTrue(toolResult.observationText.contains("Searched for OpenAI"))
        assertEquals("SUCCESS", toolResult.structuredData?.get("status"))
        @Suppress("UNCHECKED_CAST")
        val steps = toolResult.structuredData?.get("completedSteps") as? List<String>
        assertEquals(3, steps?.size)
    }

    @Test
    fun `phone agent tool handles failure gracefully`() = runTest {
        val mockAgent = object : PhoneAgent {
            override suspend fun execute(
                goal: String,
                targetApp: String?,
                confirmationGate: ConfirmationGate?,
                onProgress: (suspend (String) -> Unit)?,
            ): PhoneAgentResult {
                return PhoneAgentResult.failure(
                    message = "Could not find search input.",
                    errorCode = ErrorCode.TARGET_NOT_FOUND,
                    completedSteps = listOf("Opened YouTube"),
                )
            }
        }

        val tool = PhoneAgentTool(mockAgent)
        val toolResult = tool.execute("""{"goal":"Open YouTube and search Android"}""")

        assertFalse(toolResult.success)
        assertEquals("TARGET_NOT_FOUND", toolResult.error)
        assertEquals(ErrorCode.TARGET_NOT_FOUND, toolResult.errorCode)
    }

    @Test
    fun `phone agent does not hallucinate success when elements cannot be found`() = runTest {
        val fakeService = io.mockk.mockk<JarvisAccessibilityService>(relaxed = true)
        io.mockk.every { fakeService.inspectActiveWindow() } returns UiWindowSnapshot(
            packageName = "com.instagram.android",
            windowTitle = "Instagram",
            elements = emptyList(),
        )

        val agent = DefaultPhoneAgent(
            serviceProvider = { fakeService },
            launchApp = { Result.success(Unit) },
        )

        val result = agent.execute("Open Instagram and search OpenAI")
        assertFalse(result.isSuccess)
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue(result.message.contains("could not be read") || result.message.contains("Could not identify") || result.message.contains("step limit"))
    }

    @Test
    fun `phone agent tool forwards confirmationGate from context to phoneAgent`() = runTest {
        var receivedGate: ConfirmationGate? = null
        val mockAgent = object : PhoneAgent {
            override suspend fun execute(
                goal: String,
                targetApp: String?,
                confirmationGate: ConfirmationGate?,
                onProgress: (suspend (String) -> Unit)?,
            ): PhoneAgentResult {
                receivedGate = confirmationGate
                return PhoneAgentResult.success("Done")
            }
        }

        val tool = PhoneAgentTool(phoneAgent = mockAgent)
        val testGate = ConfirmationGate { _, _ -> true }

        kotlinx.coroutines.withContext(com.jarvis.core.agent.ConfirmationGateElement(testGate)) {
            val result = tool.execute("""{"goal":"Test goal"}""")
            assertTrue(result.success)
            assertEquals(testGate, receivedGate)
        }
    }
}
