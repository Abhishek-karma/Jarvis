package com.jarvis.core.agent

import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val LEVEL_SCHEMA = """{"type":"object","properties":{"level":{"type":"integer"}},"required":["level"]}"""

private const val EMPTY_SCHEMA = """{"type":"object","properties":{},"required":[]}"""

class AgentEngineTest {
    @Test
    fun `direct answer without tool calls ends the run`() =
        runTest {
            val audit = RecordingAudit()
            val provider =
                FakeLlmProvider(script = { listOf(ChatStreamEvent.TokenDelta("Sure."), ChatStreamEvent.Done) })
            val engine = engine(ToolRegistry(), audit)

            val events = engine.run(request(provider)).toList()

            assertEquals(
                listOf<AgentEvent>(
                    AgentEvent.RunStarted,
                    AgentEvent.IterationStarted(1),
                    AgentEvent.FinalAnswer("Sure."),
                ),
                events,
            )
            assertEquals(1, provider.requests.size)
            assertEquals(null, provider.requests.single().toolsAvailable) // no tools registered
            assertTrue(audit.records.isEmpty())
        }

    @Test
    fun `read-only tool executes without confirmation then the model answers`() =
        runTest {
            val tool = FakeTool("battery", PermissionTier.READ_ONLY)
            val registry = ToolRegistry().apply { register(tool) }
            val audit = RecordingAudit()
            val gate = RecordingGate()
            val provider =
                FakeLlmProvider().apply {
                    script = { request ->
                        if (requests.size == 1) {
                            listOf(ChatStreamEvent.ToolCallRequested("battery", """{}"""), ChatStreamEvent.Done)
                        } else {
                            listOf(ChatStreamEvent.TokenDelta("Battery at 76%."), ChatStreamEvent.Done)
                        }
                    }
                }
            val engine = engine(registry, audit, gate)

            val events = engine.run(request(provider)).toList()

            assertEquals(
                listOf<AgentEvent>(
                    AgentEvent.RunStarted,
                    AgentEvent.IterationStarted(1),
                    AgentEvent.ToolRequested("battery", """{}""", PermissionTier.READ_ONLY),
                    AgentEvent.ToolExecuting("battery"),
                    AgentEvent.ToolExecuted("battery", success = true, observationText = "ok"),
                    AgentEvent.IterationStarted(2),
                    AgentEvent.FinalAnswer("Battery at 76%."),
                ),
                events,
            )
            assertEquals(1, tool.executions)
            assertEquals("{}", tool.lastArgs)
            assertTrue(gate.asked.isEmpty()) // read-only tier is never gated
            assertEquals(1, audit.records.size)
            audit.records.single().let {
                assertEquals("battery", it.toolName)
                assertEquals("read_only", it.tier)
                assertEquals("success", it.resultStatus)
                assertEquals(false, it.userConfirmed)
            }
            assertEquals(registry.definitions(), provider.requests[0].toolsAvailable)
            // Second iteration carries the tool Observation back as context.
            assertTrue(
                provider.requests[1].conversationHistory.any { it.role == MessageRole.TOOL && it.content == "ok" },
            )
        }

    @Test
    fun `sensitive tool waits for confirmation and records userConfirmed when granted`() =
        runTest {
            val tool = FakeTool("send_email", PermissionTier.SENSITIVE)
            val registry = ToolRegistry().apply { register(tool) }
            val audit = RecordingAudit()
            val gate = RecordingGate(allow = true)
            val provider =
                FakeLlmProvider().apply {
                    script = {
                        if (requests.size == 1) {
                            listOf(
                                ChatStreamEvent.ToolCallRequested("send_email", """{"to": "a@b.c", "body": "hello"}"""),
                                ChatStreamEvent.Done,
                            )
                        } else {
                            listOf(ChatStreamEvent.TokenDelta("Sent."), ChatStreamEvent.Done)
                        }
                    }
                }
            val engine = engine(registry, audit, gate)

            val events = engine.run(request(provider)).toList()

            assertTrue(
                events.contains(AgentEvent.ConfirmationRequired("send_email", """{"to": "a@b.c", "body": "hello"}""")),
            )
            assertEquals(1, tool.executions)
            assertEquals(listOf("send_email" to """{"to": "a@b.c", "body": "hello"}"""), gate.asked)
            assertEquals(1, audit.records.size)
            audit.records.single().let {
                assertEquals("sensitive", it.tier)
                assertEquals("success", it.resultStatus)
                assertEquals(true, it.userConfirmed)
                assertFalse(it.paramsRedactedJson.contains("hello")) // body redacted, never plaintext
                assertTrue(it.paramsRedactedJson.contains("[redacted len=5 sha256="))
            }
        }

    @Test
    fun `denied sensitive call is cancelled and never executed`() =
        runTest {
            val tool = FakeTool("send_email", PermissionTier.SENSITIVE)
            val registry = ToolRegistry().apply { register(tool) }
            val audit = RecordingAudit()
            val gate = RecordingGate(allow = false)
            val provider =
                FakeLlmProvider(script = {
                    listOf(ChatStreamEvent.ToolCallRequested("send_email", """{"body": "hi"}"""), ChatStreamEvent.Done)
                })
            val engine = engine(registry, audit, gate)

            val events = engine.run(request(provider)).toList()

            assertEquals(
                listOf(AgentEvent.ToolCancelled("send_email")),
                events.filterIsInstance<AgentEvent.ToolCancelled>(),
            )
            assertEquals(0, tool.executions)
            assertEquals(1, audit.records.size)
            audit.records.single().let {
                assertEquals("cancelled", it.resultStatus)
                assertEquals(false, it.userConfirmed)
            }
            assertTrue(events.none { it is AgentEvent.FinalAnswer })
        }

    @Test
    fun `unknown tool is surfaced and the loop continues`() =
        runTest {
            val registry = ToolRegistry().apply { register(FakeTool("battery", PermissionTier.READ_ONLY)) }
            val audit = RecordingAudit()
            val provider =
                FakeLlmProvider().apply {
                    script = {
                        if (requests.size == 1) {
                            listOf(ChatStreamEvent.ToolCallRequested("nope", """{}"""), ChatStreamEvent.Done)
                        } else {
                            listOf(ChatStreamEvent.TokenDelta("I only have battery."), ChatStreamEvent.Done)
                        }
                    }
                }
            val engine = engine(registry, audit)

            val events = engine.run(request(provider)).toList()

            val rejected = events.filterIsInstance<AgentEvent.ToolRejected>().single()
            assertEquals("nope", rejected.name)
            assertTrue(rejected.reason.contains("Unknown tool"))
            assertTrue(events.contains(AgentEvent.FinalAnswer("I only have battery.")))
            assertTrue(audit.records.isEmpty())
        }

    @Test
    fun `invalid args are rejected so the model can retry, then execute succeeds`() =
        runTest {
            val tool =
                FakeTool(
                    "battery",
                    PermissionTier.READ_ONLY,
                    parametersSchemaJson = LEVEL_SCHEMA,
                )
            val registry = ToolRegistry().apply { register(tool) }
            val audit = RecordingAudit()
            val provider =
                FakeLlmProvider().apply {
                    script = {
                        when (requests.size) {
                            1 -> listOf(ChatStreamEvent.ToolCallRequested("battery", """{}"""), ChatStreamEvent.Done)
                            2 ->
                                listOf(
                                    ChatStreamEvent.ToolCallRequested("battery", """{"level": 1}"""),
                                    ChatStreamEvent.Done,
                                )
                            else -> listOf(ChatStreamEvent.TokenDelta("76%."), ChatStreamEvent.Done)
                        }
                    }
                }
            val engine = engine(registry, audit)

            val events = engine.run(request(provider)).toList()

            val rejected = events.filterIsInstance<AgentEvent.ToolRejected>().single()
            assertEquals("battery", rejected.name)
            assertTrue(rejected.reason.contains("level"))
            assertEquals(1, tool.executions) // the empty-args attempt never reached the tool
            assertEquals("""{"level": 1}""", tool.lastArgs)
            assertEquals(1, audit.records.size)
            assertEquals("success", audit.records.single().resultStatus)
            assertTrue(events.contains(AgentEvent.FinalAnswer("76%.")))
        }

    @Test
    fun `step cap reached ends the run with partial progress`() =
        runTest {
            val tool = FakeTool("battery", PermissionTier.READ_ONLY)
            val registry = ToolRegistry().apply { register(tool) }
            val audit = RecordingAudit()
            val provider =
                FakeLlmProvider(script = {
                    listOf(ChatStreamEvent.ToolCallRequested("battery", """{}"""), ChatStreamEvent.Done)
                })
            val engine = engine(registry, audit, stepCap = 2)

            val events = engine.run(request(provider)).toList()

            assertEquals(AgentEvent.StepCapReached(2), events.last())
            assertEquals(2, tool.executions)
            assertEquals(2, audit.records.size)
            assertTrue(events.none { it is AgentEvent.FinalAnswer })
        }

    @Test
    fun `provider stream error fails the run`() =
        runTest {
            val provider =
                FakeLlmProvider(script = {
                    listOf(
                        ChatStreamEvent.Error(code = "429", message = "rate limited", retryable = true),
                        ChatStreamEvent.Done,
                    )
                })
            val audit = RecordingAudit()
            val engine = engine(ToolRegistry(), audit)

            val events = engine.run(request(provider)).toList()

            assertEquals(AgentEvent.Failed(code = "429", message = "rate limited"), events.last())
            assertTrue(audit.records.isEmpty())
        }

    @Test
    fun `cancelling a run lets an in-flight tool call finish and still audits it`() =
        runTest(UnconfinedTestDispatcher()) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<ToolResult>()
            val tool =
                object : Tool {
                    override val name = "slow_tool"
                    override val description = "slow tool"
                    override val parametersSchemaJson = EMPTY_SCHEMA
                    override val tier = PermissionTier.READ_ONLY

                    override suspend fun execute(argsJson: String): ToolResult {
                        started.complete(Unit)
                        return release.await()
                    }
                }
            val registry = ToolRegistry().apply { register(tool) }
            val audit = RecordingAudit()
            val provider =
                FakeLlmProvider(script = {
                    listOf(ChatStreamEvent.ToolCallRequested("slow_tool", """{}"""), ChatStreamEvent.Done)
                })
            val engine = AgentEngine(registry, audit, RecordingGate())

            val job =
                launch {
                    engine.run(AgentRunRequest(provider, "m", listOf(userRequest("go")))).toList()
                }
            started.await() // the tool call is in flight
            job.cancel()
            release.complete(ToolResult(success = true, observationText = "finished"))

            advanceUntilIdle()

            assertEquals(1, audit.records.size)
            assertEquals("success", audit.records.single().resultStatus) // the in-flight call ran to completion
        }

    // ---- helpers ----

    private fun engine(
        registry: ToolRegistry,
        audit: RecordingAudit,
        gate: RecordingGate = RecordingGate(),
        stepCap: Int = 15,
    ) = AgentEngine(registry, audit, gate, stepCap)

    private fun request(provider: FakeLlmProvider) =
        AgentRunRequest(
            provider = provider,
            modelId = "test-model",
            messages = listOf(userRequest("Do the thing")),
            agentRunId = "run-1",
        )
}
