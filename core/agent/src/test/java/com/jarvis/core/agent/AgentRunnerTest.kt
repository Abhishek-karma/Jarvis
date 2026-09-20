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
                AgentEvent.TextDelta("Hello, user!"),
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
    fun `multiple read-only tools execute sequentially in order`() = runTest {
        val executionOrder = mutableListOf<String>()
        val tool1 = FakeTool("read_1", PermissionTier.READ_ONLY) {
            executionOrder += "read_1"
            ToolResult(true, "ok1")
        }
        val tool2 = FakeTool("read_2", PermissionTier.READ_ONLY) {
            executionOrder += "read_2"
            ToolResult(true, "ok2")
        }
        val tool3 = FakeTool("read_3", PermissionTier.READ_ONLY) {
            executionOrder += "read_3"
            ToolResult(true, "ok3")
        }
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
        assertEquals(listOf("read_1", "read_2", "read_3"), executionOrder)
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

        // Verify protocol consistency: 2nd request history includes assistant tool call followed by tool result
        val secondReq = provider.requests[1]
        val lastTwo = secondReq.conversationHistory.takeLast(2)
        assertEquals(com.jarvis.core.common.MessageRole.ASSISTANT, lastTwo[0].role)
        assertEquals("web_search", lastTwo[0].toolCallName)
        assertEquals(com.jarvis.core.common.MessageRole.TOOL, lastTwo[1].role)
        assertEquals("web_search", lastTwo[1].toolCallName)
        assertEquals(lastTwo[0].toolCallId, lastTwo[1].toolCallId)
        assertTrue(lastTwo[1].content.contains("disabled"))
    }

    @Test
    fun `policy denied tools record paired tool turn and notify model`() = runTest {
        val tool = FakeTool("custom_tool", PermissionTier.READ_ONLY)
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        var callCount = 0
        val provider = FakeLlmProvider().apply {
            script = {
                callCount++
                if (callCount == 1) {
                    listOf(ChatStreamEvent.ToolCallRequested("custom_tool", """{}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("Understood denial"), ChatStreamEvent.Done)
                }
            }
        }

        val denyPolicy = object : ToolPolicy {
            override suspend fun evaluate(tool: Tool, argsJson: String, isForceConfirm: Boolean): PolicyDecision {
                return PolicyDecision.Deny("Blocked by security policy test")
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            toolPolicy = denyPolicy,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Execute custom tool")),
            )
        ).toList()

        assertEquals(0, tool.executions)
        assertTrue(events.any { it is AgentEvent.ToolRejected && it.name == "custom_tool" })
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "Understood denial" })

        val secondReq = provider.requests[1]
        val lastTwo = secondReq.conversationHistory.takeLast(2)
        assertEquals(com.jarvis.core.common.MessageRole.ASSISTANT, lastTwo[0].role)
        assertEquals("custom_tool", lastTwo[0].toolCallName)
        assertEquals(com.jarvis.core.common.MessageRole.TOOL, lastTwo[1].role)
        assertEquals("custom_tool", lastTwo[1].toolCallName)
        assertEquals(lastTwo[0].toolCallId, lastTwo[1].toolCallId)
        assertTrue(lastTwo[1].content.contains("Blocked by security policy test"))
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

    @Test
    fun `tool execution failure returns error observation to model`() = runTest {
        val failingTool = object : Tool {
            override val name = "failing_tool"
            override val description = "fails on purpose"
            override val parametersSchemaJson = """{}"""
            override val tier = PermissionTier.READ_ONLY
            override suspend fun execute(argsJson: String): ToolResult =
                ToolResult(success = false, observationText = "Failed to connect to service.", error = "network timeout")
        }
        val registry = ToolRegistry().apply { register(failingTool) }
        val audit = RecordingAudit()
        var turnCount = 0
        val provider = FakeLlmProvider().apply {
            script = { req ->
                turnCount++
                if (turnCount == 1) {
                    listOf(ChatStreamEvent.ToolCallRequested("failing_tool", """{}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("I noticed the failure and handled it gracefully."), ChatStreamEvent.Done)
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Execute failing tool")),
            )
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolExecuting && it.name == "failing_tool" })
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text.contains("handled it gracefully") })
        val secondReq = provider.requests[1]
        val toolMessage = secondReq.conversationHistory.last()
        assertEquals(com.jarvis.core.common.MessageRole.TOOL, toolMessage.role)
        assertTrue(toolMessage.content.contains("Failed to connect to service."))
    }

    @Test
    fun `invalid arguments are rejected and returned as tool turn`() = runTest {
        val schemaTool = object : Tool {
            override val name = "calc_tool"
            override val description = "calculator"
            override val parametersSchemaJson = """{"type":"object","properties":{"expr":{"type":"string"}},"required":["expr"]}"""
            override val tier = PermissionTier.READ_ONLY
            override suspend fun execute(argsJson: String): ToolResult = ToolResult(true, "42")
        }
        val registry = ToolRegistry().apply { register(schemaTool) }
        val audit = RecordingAudit()
        var turnCount = 0
        val provider = FakeLlmProvider().apply {
            script = { req ->
                turnCount++
                if (turnCount == 1) {
                    // Send invalid arguments missing "expr"
                    listOf(ChatStreamEvent.ToolCallRequested("calc_tool", """{"wrong_field":"1+1"}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("Recovered from invalid args"), ChatStreamEvent.Done)
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Calculate")),
            )
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolRejected && it.name == "calc_tool" })
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "Recovered from invalid args" })
    }

    @Test
    fun `unknown tool is rejected and allows model recovery`() = runTest {
        val registry = ToolRegistry()
        val audit = RecordingAudit()
        var turnCount = 0
        val provider = FakeLlmProvider().apply {
            script = { req ->
                turnCount++
                if (turnCount == 1) {
                    listOf(ChatStreamEvent.ToolCallRequested("nonexistent_tool", """{}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("I will answer without the tool."), ChatStreamEvent.Done)
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Use magic")),
            )
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolRejected && it.name == "nonexistent_tool" })
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "I will answer without the tool." })
    }

    @Test
    fun `raw text containing pseudo tool call syntax is treated as plain text and does not execute tools`() = runTest {
        val tool = FakeTool("get_battery", PermissionTier.READ_ONLY)
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val provider = FakeLlmProvider().apply {
            script = { _ ->
                listOf(
                    ChatStreamEvent.TokenDelta("<|toolcall>{\"name\":\"get_battery\"}<|call_end|>"),
                    ChatStreamEvent.Done,
                )
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Check battery")),
            )
        ).toList()

        assertEquals(0, tool.executions, "Text containing pseudo toolcall syntax must not execute tools")
        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().firstOrNull()
        assertTrue(finalAnswer != null && finalAnswer.text.contains("<|toolcall>"))
    }
}
