package com.jarvis.core.agent

import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import com.jarvis.core.agent.execution.ExecutionStatus
import com.jarvis.core.agent.execution.ErrorCode
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

        assertEquals(AgentEvent.RunStarted, events[0])
        assertEquals(AgentEvent.IterationStarted(1), events[1])
        assertEquals(AgentEvent.TextDelta("Hello, user!"), events[2])
        val finalEvent = events[3] as AgentEvent.FinalAnswer
        assertEquals("Hello, user!", finalEvent.text)
        assertTrue(finalEvent.executionResult?.isSuccess == true)
    }

    /**
     * Regression: real runs end on a tool-call turn followed by a prose-free turn, which used to
     * emit `FinalAnswer("")` and leave the transcript with only the agent card ("✓ Done").
     */
    @Test
    fun `blank final turn after tool calls never yields an empty answer`() = runTest {
        val tool = FakeTool("current_time", PermissionTier.READ_ONLY)
        val registry = ToolRegistry().apply { register(tool) }
        var call = 0
        val provider =
            FakeLlmProvider(
                script = {
                    if (call++ == 0) {
                        listOf(ChatStreamEvent.ToolCallRequested("current_time", "{}"), ChatStreamEvent.Done)
                    } else {
                        listOf(ChatStreamEvent.Done)
                    }
                },
            )
        val runner = AgentRunner(
            registry = registry,
            audit = RecordingAudit(),
            confirmationGate = RecordingGate(),
        )

        val events = runner
            .run(
                AgentRunRequest(
                    provider = provider,
                    modelId = "test",
                    messages = listOf(userRequest("What time is it?")),
                ),
            ).toList()

        assertEquals(1, tool.executions)
        val finalEvent = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertTrue(finalEvent.text.isNotBlank(), "FinalAnswer text must never be blank")
        assertEquals("Action 'current_time' completed with result: ok.", finalEvent.text)
        assertEquals(false, finalEvent.executionResult?.userMessage.isNullOrBlank())
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

    @Test
    fun `failure followed by successful retry results in SUCCESS`() = runTest {
        var execs = 0
        val tool = FakeTool("retry_tool", PermissionTier.READ_ONLY) {
            execs++
            if (execs == 1) {
                ToolResult(success = false, observationText = "Connection lost", error = "TIMEOUT")
            } else {
                ToolResult(success = true, observationText = "Connection restored")
            }
        }
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        var turn = 0
        val provider = FakeLlmProvider().apply {
            script = {
                turn++
                if (turn <= 2) {
                    listOf(ChatStreamEvent.ToolCallRequested("retry_tool", "{}"), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("Got it!"), ChatStreamEvent.Done)
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
                messages = listOf(userRequest("Try connecting")),
            )
        ).toList()

        val finalAns = events.filterIsInstance<AgentEvent.FinalAnswer>().firstOrNull()
        assertNotNull(finalAns)
        assertEquals("Got it!", finalAns?.text)
        assertEquals(ExecutionStatus.SUCCESS, finalAns?.executionResult?.status)
    }

    @Test
    fun `failure after previous successful step results in PARTIAL_SUCCESS`() = runTest {
        val tool1 = FakeTool("ok_tool", PermissionTier.READ_ONLY) {
            ToolResult(success = true, observationText = "Step 1 ok")
        }
        val tool2 = FakeTool("fail_tool", PermissionTier.READ_ONLY) {
            ToolResult(success = false, observationText = "Step 2 failed", error = "TARGET_NOT_FOUND")
        }
        val registry = ToolRegistry().apply {
            register(tool1)
            register(tool2)
        }
        val audit = RecordingAudit()
        var turn = 0
        val provider = FakeLlmProvider().apply {
            script = {
                turn++
                when (turn) {
                    1 -> listOf(ChatStreamEvent.ToolCallRequested("ok_tool", "{}"), ChatStreamEvent.Done)
                    2 -> listOf(ChatStreamEvent.ToolCallRequested("fail_tool", "{}"), ChatStreamEvent.Done)
                    else -> listOf(ChatStreamEvent.TokenDelta("Task partial"), ChatStreamEvent.Done)
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
                messages = listOf(userRequest("Do both")),
            )
        ).toList()

        val finalAns = events.filterIsInstance<AgentEvent.FinalAnswer>().firstOrNull()
        assertNotNull(finalAns)
        assertEquals(ExecutionStatus.PARTIAL_SUCCESS, finalAns?.executionResult?.status)
        assertEquals(listOf("ok_tool"), finalAns?.executionResult?.completedSteps)
        assertEquals(ErrorCode.TARGET_NOT_FOUND, finalAns?.executionResult?.code)
    }

    @Test
    fun `sensitive tool confirmation cancel results in CANCELLED`() = runTest {
        val tool = FakeTool("sensitive_tool", PermissionTier.SENSITIVE)
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val gate = RecordingGate(allow = false)
        val provider = FakeLlmProvider().apply {
            script = {
                listOf(ChatStreamEvent.ToolCallRequested("sensitive_tool", "{}"), ChatStreamEvent.Done)
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = gate,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Run sensitive")),
            )
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolCancelled })
        assertTrue(events.any { it is AgentEvent.Cancelled })
        assertEquals(0, tool.executions)
    }

    @Test
    fun `sensitive tool confirmation approve and execute results in SUCCESS`() = runTest {
        val tool = FakeTool("sensitive_tool", PermissionTier.SENSITIVE) {
            ToolResult(success = true, observationText = "Sensitive action done")
        }
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val gate = RecordingGate(allow = true)
        var turn = 0
        val provider = FakeLlmProvider().apply {
            script = {
                turn++
                if (turn == 1) {
                    listOf(ChatStreamEvent.ToolCallRequested("sensitive_tool", "{}"), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("Success!"), ChatStreamEvent.Done)
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = gate,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Run sensitive")),
            )
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolExecuting })
        assertTrue(events.any { it is AgentEvent.ToolExecuted && it.success })
        val finalAns = events.filterIsInstance<AgentEvent.FinalAnswer>().firstOrNull()
        assertNotNull(finalAns)
        assertEquals(ExecutionStatus.SUCCESS, finalAns?.executionResult?.status)
    }

    @Test
    fun `repeated side-effecting tool with same args is stopped with LOOP_DETECTED and safe useful result`() = runTest {
        val tool = FakeTool("play_music", PermissionTier.REVERSIBLE_WRITE) {
            ToolResult(success = true, observationText = "Playing song A")
        }
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val provider = FakeLlmProvider().apply {
            script = {
                listOf(
                    ChatStreamEvent.ToolCallRequested("play_music", """{"song": "A"}"""),
                    ChatStreamEvent.Done,
                )
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            stepCap = 10,
            maxSideEffectRepetitions = 2,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Play song A")),
            )
        ).toList()

        // Side-effecting tool should execute exactly 2 times and be halted before the 3rd execution
        assertEquals(2, tool.executions)
        val loopFailed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        assertNotNull(loopFailed)
        assertEquals(ErrorCode.LOOP_DETECTED, loopFailed?.code)

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().firstOrNull()
        assertNotNull(finalAnswer)
        assertEquals(listOf("play_music", "play_music"), finalAnswer?.executionResult?.completedSteps)
        assertTrue(finalAnswer?.text?.contains("play_music") == true)
    }

    @Test
    fun `argument normalization detects loops across different key orders and whitespace`() = runTest {
        val tool = FakeTool("set_settings", PermissionTier.REVERSIBLE_WRITE) {
            ToolResult(success = true, observationText = "Settings updated")
        }
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        var callCount = 0
        val provider = FakeLlmProvider().apply {
            script = {
                callCount++
                val args = if (callCount % 2 == 1) {
                    """{"key": "volume", "val": 10}"""
                } else {
                    """{ "val" : 10 ,  "key" : "volume" }"""
                }
                listOf(
                    ChatStreamEvent.ToolCallRequested("set_settings", args),
                    ChatStreamEvent.Done,
                )
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            stepCap = 10,
            maxSideEffectRepetitions = 2,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Set volume")),
            )
        ).toList()

        assertEquals(2, tool.executions)
        val failed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        assertNotNull(failed)
        assertEquals(ErrorCode.LOOP_DETECTED, failed?.code)
    }

    @Test
    fun `read-only tools allow higher repetition than side effects before stopping`() = runTest {
        val tool = FakeTool("get_status", PermissionTier.READ_ONLY) {
            ToolResult(success = true, observationText = "status: pending")
        }
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        val provider = FakeLlmProvider().apply {
            script = {
                listOf(
                    ChatStreamEvent.ToolCallRequested("get_status", "{}"),
                    ChatStreamEvent.Done,
                )
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            stepCap = 10,
            maxReadOnlyRepetitions = 3,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Check status")),
            )
        ).toList()

        // Read-only tool should be allowed 3 executions, 4th triggers LOOP_DETECTED
        assertEquals(3, tool.executions)
        val loopFailed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        assertNotNull(loopFailed)
        assertEquals(ErrorCode.LOOP_DETECTED, loopFailed?.code)
    }

    @Test
    fun `observation change on read-only tool allows progress and does not trigger loop detection`() = runTest {
        var statusIndex = 0
        val statuses = listOf("Progress: 20%", "Progress: 60%", "Progress: 100%")
        val tool = FakeTool("check_download", PermissionTier.READ_ONLY) {
            val text = statuses.getOrElse(statusIndex) { "Complete" }
            statusIndex++
            ToolResult(success = true, observationText = text)
        }
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        var calls = 0
        val provider = FakeLlmProvider().apply {
            script = {
                calls++
                if (calls <= 3) {
                    listOf(
                        ChatStreamEvent.ToolCallRequested("check_download", "{}"),
                        ChatStreamEvent.Done,
                    )
                } else {
                    listOf(
                        ChatStreamEvent.TokenDelta("Download completed!"),
                        ChatStreamEvent.Done,
                    )
                }
            }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            stepCap = 10,
            maxReadOnlyRepetitions = 2,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Monitor download")),
            )
        ).toList()

        // Because observation changed each time, 3 calls were allowed even though maxReadOnlyRepetitions is 2
        assertEquals(3, tool.executions)
        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().firstOrNull()
        assertNotNull(finalAnswer)
        assertEquals("Download completed!", finalAnswer?.text)
        assertTrue(events.none { it is AgentEvent.Failed && it.code == ErrorCode.LOOP_DETECTED })
    }

    @Test
    fun `overall execution timeout stops safely and emits useful result`() = runTest {
        val tool = FakeTool("fast_tool", PermissionTier.READ_ONLY) {
            ToolResult(success = true, observationText = "Fast ok")
        }
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()
        var turn = 0
        val provider = FakeLlmProvider().apply {
            script = {
                turn++
                if (turn == 1) {
                    listOf(
                        ChatStreamEvent.ToolCallRequested("fast_tool", "{}"),
                        ChatStreamEvent.Done,
                    )
                } else {
                    // Simulating a hanging response using a flow that delays
                    emptyList()
                }
            }
        }

        val hangingProvider = object : FakeLlmProvider() {
            override fun streamChat(request: com.jarvis.core.network.ChatRequest): kotlinx.coroutines.flow.Flow<ChatStreamEvent> =
                kotlinx.coroutines.flow.flow {
                    if (turn == 0) {
                        turn++
                        emit(ChatStreamEvent.ToolCallRequested("fast_tool", "{}"))
                        emit(ChatStreamEvent.Done)
                    } else {
                        // Hang beyond timeout
                        delay(5000L)
                        emit(ChatStreamEvent.Done)
                    }
                }
        }

        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            executionTimeoutMillis = 150L,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = hangingProvider,
                modelId = "test",
                messages = listOf(userRequest("Do work")),
                timeoutMillis = 150L,
            )
        ).toList()

        assertEquals(1, tool.executions)
        val timeoutFailed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        assertNotNull(timeoutFailed)
        assertEquals(ErrorCode.TIMEOUT, timeoutFailed?.code)

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().firstOrNull()
        assertNotNull(finalAnswer)
        assertEquals(listOf("fast_tool"), finalAnswer?.executionResult?.completedSteps)
    }

    @Test
    fun `calendar query explains events and never returns Task completed when model returns empty or generic`() = runTest {
        val calendarTool = object : Tool {
            override val name = "list_events"
            override val tier = PermissionTier.READ_ONLY
            override val description = "List calendar events"
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""
            override suspend fun execute(argsJson: String): ToolResult {
                return ToolResult(
                    success = true,
                    observationText = "2 event(s):\n- \"Team Standup\" 09:00 UTC @ Room 1\n- \"Lunch with Sarah\" 12:30 UTC",
                )
            }
        }
        val registry = ToolRegistry().apply { register(calendarTool) }
        var turn = 0
        // Model requests list_events, then emits generic "Task completed."
        val provider = FakeLlmProvider(
            script = {
                if (turn++ == 0) {
                    listOf(ChatStreamEvent.ToolCallRequested("list_events", "{}"), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.TokenDelta("Task completed."), ChatStreamEvent.Done)
                }
            },
        )
        val runner = AgentRunner(
            registry = registry,
            audit = RecordingAudit(),
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("What is on my calendar today?")),
            ),
        ).toList()

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertFalse(finalAnswer.text.contains("Task completed"), "Must never say Task completed.")
        assertTrue(finalAnswer.text.contains("Team Standup"), "Must explain the events from observation.")
        assertTrue(finalAnswer.text.contains("Lunch with Sarah"), "Must explain Lunch with Sarah.")
    }

    @Test
    fun `play music succeeded explains what happened`() = runTest {
        val mediaTool = object : Tool {
            override val name = "play_media"
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val description = "Play music"
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""
            override suspend fun execute(argsJson: String): ToolResult {
                return ToolResult(success = true, observationText = "Playing 'Bohemian Rhapsody' on Spotify.")
            }
        }
        val registry = ToolRegistry().apply { register(mediaTool) }
        var turn = 0
        // Model returns empty prose after tool call
        val provider = FakeLlmProvider(
            script = {
                if (turn++ == 0) {
                    listOf(ChatStreamEvent.ToolCallRequested("play_media", """{"query":"Bohemian Rhapsody"}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.Done)
                }
            },
        )
        val runner = AgentRunner(
            registry = registry,
            audit = RecordingAudit(),
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Play music")),
            ),
        ).toList()

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("Started playing 'Bohemian Rhapsody' on Spotify.", finalAnswer.text)
        assertFalse(finalAnswer.text.contains("Task completed"))
        assertFalse(finalAnswer.text.contains("Done"))
    }

    @Test
    fun `play music failed explains the failure`() = runTest {
        val mediaTool = object : Tool {
            override val name = "play_media"
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val description = "Play music"
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""
            override suspend fun execute(argsJson: String): ToolResult {
                return ToolResult(success = false, observationText = "Spotify is not installed on this device.", error = "APP_NOT_FOUND")
            }
        }
        val registry = ToolRegistry().apply { register(mediaTool) }
        var turn = 0
        val provider = FakeLlmProvider(
            script = {
                if (turn++ == 0) {
                    listOf(ChatStreamEvent.ToolCallRequested("play_media", """{"query":"jazz"}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.Done)
                }
            },
        )
        val runner = AgentRunner(
            registry = registry,
            audit = RecordingAudit(),
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Play music")),
            ),
        ).toList()

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertTrue(finalAnswer.text.startsWith("I couldn't play music because"), "Must explain failure using contract")
        assertTrue(finalAnswer.text.contains("Spotify is not installed"), "Must explain failure")
        assertFalse(finalAnswer.text.contains("Task completed"))
    }

    @Test
    fun `play music uncertain explicitly says outcome is uncertain`() = runTest {
        val mediaTool = object : Tool {
            override val name = "play_media"
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val description = "Play music"
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""
            override suspend fun execute(argsJson: String): ToolResult {
                return ToolResult(
                    success = true,
                    observationText = "Playback command dispatched, but playback state is uncertain.",
                )
            }
        }
        val registry = ToolRegistry().apply { register(mediaTool) }
        var turn = 0
        val provider = FakeLlmProvider(
            script = {
                if (turn++ == 0) {
                    listOf(ChatStreamEvent.ToolCallRequested("play_media", """{"query":"lofi"}"""), ChatStreamEvent.Done)
                } else {
                    listOf(ChatStreamEvent.Done)
                }
            },
        )
        val runner = AgentRunner(
            registry = registry,
            audit = RecordingAudit(),
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("Play music")),
            ),
        ).toList()

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("I started the operation, but I can't confirm whether it completed.", finalAnswer.text)
        assertFalse(finalAnswer.text.contains("Task completed"))
    }

    @Test
    fun `internal architecture terms are sanitized from final answer`() = runTest {
        var turn = 0
        val provider = FakeLlmProvider(
            script = {
                listOf(
                    ChatStreamEvent.TokenDelta("AgentRunner completed using ToolExecutor and TaskEngine with OperationRepository checking idempotency on execution state."),
                    ChatStreamEvent.Done,
                )
            },
        )
        val runner = AgentRunner(
            registry = ToolRegistry(),
            audit = RecordingAudit(),
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = "test",
                messages = listOf(userRequest("What happened?")),
            ),
        ).toList()

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertFalse(finalAnswer.text.contains("AgentRunner", ignoreCase = true))
        assertFalse(finalAnswer.text.contains("ToolExecutor", ignoreCase = true))
        assertFalse(finalAnswer.text.contains("TaskEngine", ignoreCase = true))
        assertFalse(finalAnswer.text.contains("OperationRepository", ignoreCase = true))
        assertFalse(finalAnswer.text.contains("idempotency", ignoreCase = true))
        assertFalse(finalAnswer.text.contains("execution state", ignoreCase = true))
    }
}
