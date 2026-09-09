package com.jarvis.core.agent

import com.jarvis.core.network.ChatStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentRunnerTest {

    @Test
    fun `direct answer without tools finishes immediately`() = runTest {
        val audit = RecordingAudit()
        val provider = FakeLlmProvider(script = { listOf(ChatStreamEvent.TokenDelta("Hello, user!"), ChatStreamEvent.Done) })
        val runner = AgentRunner(
            registry = ToolRegistry(),
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Hi")),
            )
        ).toList()

        assertEquals(
            listOf(
                AgentEvent.RunStarted,
                AgentEvent.IterationStarted(1),
                AgentEvent.FinalAnswer("Hello, user!"),
            ),
            events,
        )
    }

    @Test
    fun `sensitive tool enforces policy confirmation gate`() = runTest {
        val tool = FakeTool("delete_account", PermissionTier.SENSITIVE)
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val gate = RecordingGate(allow = false) // user cancels / denies
        val provider = FakeLlmProvider().apply {
            script = {
                listOf(ChatStreamEvent.ToolCallRequested("delete_account", """{}"""), ChatStreamEvent.Done)
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = gate,
            toolPolicy = DefaultToolPolicy(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Delete it")),
            )
        ).toList()

        assertTrue(events.any { it is AgentEvent.ConfirmationRequired })
        assertTrue(events.any { it is AgentEvent.ToolCancelled })
        assertEquals(0, tool.executions)
    }

    @Test
    fun `concurrent read-only tools respect bounded parallel limit`() = runTest {
        val tool1 = FakeTool("read_1", PermissionTier.READ_ONLY)
        val tool2 = FakeTool("read_2", PermissionTier.READ_ONLY)
        val tool3 = FakeTool("read_3", PermissionTier.READ_ONLY)
        val registry = ToolRegistry().apply {
            register(tool1)
            register(tool2)
            register(tool3)
        }
        val audit = RecordingAudit()
        val provider = FakeLlmProvider().apply {
            script = { request ->
                if (requests.size == 1) {
                    listOf(
                        ChatStreamEvent.ToolCallRequested("read_1", """{}"""),
                        ChatStreamEvent.ToolCallRequested("read_2", """{}"""),
                        ChatStreamEvent.ToolCallRequested("read_3", """{}"""),
                        ChatStreamEvent.Done,
                    )
                } else {
                    listOf(ChatStreamEvent.TokenDelta("All done"), ChatStreamEvent.Done)
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            parallelReadLimit = 2,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Read all")),
            )
        ).toList()

        assertEquals(1, tool1.executions)
        assertEquals(1, tool2.executions)
        assertEquals(1, tool3.executions)
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "All done" })
    }

    @Test
    fun `cancelling runner collection terminates the job`() = runTest {
        val started = CompletableDeferred<Unit>()
        val slowTool = object : Tool {
            override val name = "slow"
            override val description = "slow"
            override val parametersSchemaJson = """{}"""
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                started.complete(Unit)
                delay(10_000)
                return ToolResult(true, "finished")
            }
        }

        val registry = ToolRegistry().apply { register(slowTool) }
        val audit = RecordingAudit()
        val provider = FakeLlmProvider().apply {
            script = {
                listOf(ChatStreamEvent.ToolCallRequested("slow", """{}"""), ChatStreamEvent.Done)
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val job = launch {
            runner.run(
                AgentRunRequest(
                    provider = provider,
                    modelId = "test",
                    messages = listOf(userRequest("Do slow")),
                )
            ).toList()
        }

        started.await()
        job.cancel()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `step cap terminates loop with step cap reached event`() = runTest {
        val tool = FakeTool("always_calls", PermissionTier.READ_ONLY)
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val provider = FakeLlmProvider().apply {
            script = {
                listOf(ChatStreamEvent.ToolCallRequested("always_calls", """{}"""), ChatStreamEvent.Done)
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            stepCap = 3,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Loop forever")),
            )
        ).toList()

        assertEquals(3, tool.executions)
        assertTrue(events.any { it is AgentEvent.StepCapReached && it.stepsUsed == 3 })
    }

    @Test
    fun `disabled tools are rejected and reported to model`() = runTest {
        val tool = FakeTool("web_search", PermissionTier.READ_ONLY)
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        var callCount = 0
        val provider = FakeLlmProvider().apply {
            script = {
                callCount++
                if (callCount == 1) {
                    listOf(ChatStreamEvent.ToolCallRequested("web_search", """{"q":"test"}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("Search disabled acknowledged"), ChatStreamEvent.Done)
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            disabledTools = setOf("web_search"),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Search web")),
            )
        ).toList()

        assertEquals(0, tool.executions)
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "Search disabled acknowledged" })
    }

    @Test
    fun `force confirm triggers confirmation gate for non-read-only tools`() = runTest {
        val tool = FakeTool("write_setting", PermissionTier.REVERSIBLE_WRITE)
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val gate = RecordingGate(allow = true)
        val provider = FakeLlmProvider().apply {
            script = { req ->
                if (req.conversationHistory.size == 1) {
                    listOf(ChatStreamEvent.ToolCallRequested("write_setting", """{}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("Setting written"), ChatStreamEvent.Done)
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = gate,
            forceConfirm = true,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Write setting")),
            )
        ).toList()

        assertTrue(events.any { it is AgentEvent.ConfirmationRequired && it.name == "write_setting" })
        assertEquals(1, tool.executions)
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "Setting written" })
    }
}
