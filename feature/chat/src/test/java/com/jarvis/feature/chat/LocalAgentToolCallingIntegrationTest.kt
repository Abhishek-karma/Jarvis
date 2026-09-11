package com.jarvis.feature.chat

import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.AuditRecord
import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.agent.tools.CalculatorTool
import com.jarvis.core.agent.tools.SystemInfoTools
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.ml.LocalLlmProvider
import com.jarvis.core.ml.LocalModelSpec
import com.jarvis.core.ml.OnDeviceEngine
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ToolDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end integration tests verifying local-model agent tool calling:
 * USER -> LOCAL LLM -> TOOL CALL -> VALIDATION -> POLICY -> TOOL EXECUTION -> TOOL RESULT -> SAME LOCAL LLM CONVERSATION -> FINAL ANSWER
 */
class LocalAgentToolCallingIntegrationTest {

    private val toolCapableSpec = LocalModelSpec(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it-web.litertlm",
        supportsTools = true,
        supportsReasoning = false,
    )

    private val chatOnlySpec = LocalModelSpec(
        id = "qwen3-0.6b",
        displayName = "Qwen 3 0.6B",
        fileName = "qwen3.litertlm",
        supportsTools = false,
        supportsReasoning = false,
    )

    private fun userRequest(text: String) =
        Message(
            id = "msg-1",
            conversationId = "conv-local",
            role = MessageRole.USER,
            content = text,
        )

    private class RecordingAudit : AuditLogger {
        val records = mutableListOf<AuditRecord>()
        override suspend fun record(entry: AuditRecord) {
            records += entry
        }
    }

    private class RecordingGate(var allow: Boolean = true) : ConfirmationGate {
        val asked = mutableListOf<Pair<String, String>>()
        override suspend fun confirm(toolName: String, argsJson: String): Boolean {
            asked += toolName to argsJson
            return allow
        }
    }

    private class FakeTool(
        override val name: String,
        override val tier: PermissionTier = PermissionTier.READ_ONLY,
        override val parametersSchemaJson: String = """{"type":"object","properties":{}}""",
        private val behavior: (String) -> ToolResult = { ToolResult(true, "ok") },
    ) : Tool {
        override val description: String = "Test tool $name"
        var executions: Int = 0
        var lastArgs: String? = null

        override suspend fun execute(argsJson: String): ToolResult {
            executions++
            lastArgs = argsJson
            return behavior(argsJson)
        }
    }

    /**
     * Scriptable on-device engine simulating LiteRtLmEngine responses.
     */
    private class ScriptableLocalEngine(
        var handler: (conversationHistory: List<Message>, tools: List<ToolDefinition>?) -> List<ChatStreamEvent>,
    ) : OnDeviceEngine {
        val receivedConversations = mutableListOf<List<Message>>()
        val receivedTools = mutableListOf<List<ToolDefinition>?>()

        override fun streamChat(
            conversationHistory: List<Message>,
            systemPrompt: String?,
            tools: List<ToolDefinition>?,
            temperature: Double?,
        ): Flow<ChatStreamEvent> = flow {
            receivedConversations += conversationHistory
            receivedTools += tools
            val events = handler(conversationHistory, tools)
            for (event in events) {
                emit(event)
            }
        }

        override suspend fun generate(
            prompt: String,
            temperature: Double?,
            onPartial: (String) -> Unit,
            onDone: () -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            onPartial("Direct response")
            onDone()
        }

        override fun close() = Unit
    }

    private fun createLocalProvider(spec: LocalModelSpec, engine: OnDeviceEngine): LlmProvider =
        LocalLlmProvider(id = "local-${spec.id}", spec = spec, engine = engine)

    @Test
    fun `1 Local chat without tools gives direct final answer`() = runTest {
        val engine = ScriptableLocalEngine { _, _ ->
            listOf(
                ChatStreamEvent.TokenDelta("Hello! I am running on-device."),
                ChatStreamEvent.Done,
            )
        }
        val provider = createLocalProvider(toolCapableSpec, engine)
        val registry = ToolRegistry()
        val audit = RecordingAudit()
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Hello")),
            ),
        ).toList()

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("Hello! I am running on-device.", finalAnswer.text)
        assertEquals(1, engine.receivedConversations.size)
        assertTrue(audit.records.isEmpty())
    }

    @Test
    fun `2 Local model invokes calculator tool, gets result, and generates final answer in same conversation`() = runTest {
        val calcTool = CalculatorTool.create()
        val registry = ToolRegistry().apply { register(calcTool) }
        val audit = RecordingAudit()
        val gate = RecordingGate()

        lateinit var localEngine: ScriptableLocalEngine
        localEngine = ScriptableLocalEngine { history, _ ->
            when (localEngine.receivedConversations.size) {
                1 -> {
                    // Turn 1: Model requests calculator tool call
                    listOf(
                        ChatStreamEvent.TokenDelta("Let me calculate that. "),
                        ChatStreamEvent.ToolCallRequested(
                            name = "calculator",
                            argsJson = """{"expression":"24 * 7"}""",
                            id = "call-calc-1",
                        ),
                        ChatStreamEvent.Done,
                    )
                }
                2 -> {
                    // Turn 2: Verify history contains Tool Result and return final answer
                    val toolMsg = history.find { it.role == MessageRole.TOOL }
                    requireNotNull(toolMsg) { "Tool message must be in conversation history" }
                    assertTrue(toolMsg.content.contains("168"))

                    listOf(
                        ChatStreamEvent.TokenDelta("24 * 7 is equal to 168."),
                        ChatStreamEvent.Done,
                    )
                }
                else -> listOf(ChatStreamEvent.Done)
            }
        }

        val provider = createLocalProvider(toolCapableSpec, localEngine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = gate,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("What is 24 * 7?")),
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolRequested && it.name == "calculator" })
        assertTrue(events.any { it is AgentEvent.ToolExecuted && it.name == "calculator" && it.success })
        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("24 * 7 is equal to 168.", finalAnswer.text)

        // Verify conversation continuity
        assertEquals(2, localEngine.receivedConversations.size)
        val secondTurnHistory = localEngine.receivedConversations[1]
        assertEquals(3, secondTurnHistory.size) // User -> Assistant (with tool call) -> Tool observation
        assertEquals(MessageRole.USER, secondTurnHistory[0].role)
        assertEquals(MessageRole.ASSISTANT, secondTurnHistory[1].role)
        assertEquals("calculator", secondTurnHistory[1].toolCallName)
        assertEquals(MessageRole.TOOL, secondTurnHistory[2].role)
        assertEquals("24 * 7 = 168", secondTurnHistory[2].content)

        // Verify audit log
        assertEquals(1, audit.records.size)
        assertEquals("calculator", audit.records[0].toolName)
        assertEquals("success", audit.records[0].resultStatus)
    }

    @Test
    fun `3 Local model invokes battery tool, receives status, and answers user`() = runTest {
        val batteryTool = SystemInfoTools.batteryLevel { 85 }
        val registry = ToolRegistry().apply { register(batteryTool) }
        val audit = RecordingAudit()

        lateinit var localEngine: ScriptableLocalEngine
        localEngine = ScriptableLocalEngine { _, _ ->
            if (localEngine.receivedConversations.size == 1) {
                listOf(
                    ChatStreamEvent.ToolCallRequested(
                        name = "battery_level",
                        argsJson = """{}""",
                        id = "call-battery-1",
                    ),
                    ChatStreamEvent.Done,
                )
            } else {
                listOf(
                    ChatStreamEvent.TokenDelta("Your battery is currently at 85%."),
                    ChatStreamEvent.Done,
                )
            }
        }

        val provider = createLocalProvider(toolCapableSpec, localEngine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Check my battery")),
            ),
        ).toList()

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("Your battery is currently at 85%.", finalAnswer.text)
        assertEquals(1, audit.records.size)
        assertEquals("battery_level", audit.records[0].toolName)
    }

    @Test
    fun `4 Two-step sequential tool execution chaining tool A then tool B`() = runTest {
        val batteryTool = SystemInfoTools.batteryLevel { 42 }
        val timeTool = SystemInfoTools.currentTime(nowUtcMillis = { 1_700_000_000_000L })
        val registry = ToolRegistry().apply {
            register(batteryTool)
            register(timeTool)
        }
        val audit = RecordingAudit()

        lateinit var localEngine: ScriptableLocalEngine
        localEngine = ScriptableLocalEngine { _, _ ->
            when (localEngine.receivedConversations.size) {
                1 -> listOf(
                    ChatStreamEvent.ToolCallRequested("battery_level", "{}", id = "c1"),
                    ChatStreamEvent.Done,
                )
                2 -> listOf(
                    ChatStreamEvent.ToolCallRequested("current_time", "{}", id = "c2"),
                    ChatStreamEvent.Done,
                )
                3 -> listOf(
                    ChatStreamEvent.TokenDelta("Battery is 42% and time is recorded."),
                    ChatStreamEvent.Done,
                )
                else -> listOf(ChatStreamEvent.Done)
            }
        }

        val provider = createLocalProvider(toolCapableSpec, localEngine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Status check")),
            ),
        ).toList()

        val toolEvents = events.filterIsInstance<AgentEvent.ToolExecuted>()
        assertEquals(2, toolEvents.size)
        assertEquals("battery_level", toolEvents[0].name)
        assertEquals("current_time", toolEvents[1].name)
        assertEquals(2, audit.records.size)

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("Battery is 42% and time is recorded.", finalAnswer.text)
    }

    @Test
    fun `5 Unknown tool call is rejected gracefully and loop continues to explanation`() = runTest {
        val registry = ToolRegistry().apply {
            register(SystemInfoTools.batteryLevel { 80 })
        }
        val audit = RecordingAudit()

        lateinit var localEngine: ScriptableLocalEngine
        localEngine = ScriptableLocalEngine { _, _ ->
            if (localEngine.receivedConversations.size == 1) {
                listOf(
                    ChatStreamEvent.ToolCallRequested("non_existent_tool", """{"param":1}""", id = "c-err"),
                    ChatStreamEvent.Done,
                )
            } else {
                listOf(
                    ChatStreamEvent.TokenDelta("I do not have access to that capability, but I can check battery."),
                    ChatStreamEvent.Done,
                )
            }
        }

        val provider = createLocalProvider(toolCapableSpec, localEngine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Do something unknown")),
            ),
        ).toList()

        val rejected = events.filterIsInstance<AgentEvent.ToolRejected>().single()
        assertEquals("non_existent_tool", rejected.name)
        assertTrue(rejected.reason.contains("Unknown tool"))

        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertTrue(finalAnswer.text.contains("do not have access"))
    }

    @Test
    fun `6 Invalid tool arguments are rejected allowing model to retry with valid arguments`() = runTest {
        val tool = FakeTool(
            name = "set_brightness",
            tier = PermissionTier.REVERSIBLE_WRITE,
            parametersSchemaJson = """{"type":"object","properties":{"level":{"type":"integer"}},"required":["level"]}""",
        )
        val registry = ToolRegistry().apply { register(tool) }
        val audit = RecordingAudit()

        lateinit var localEngine: ScriptableLocalEngine
        localEngine = ScriptableLocalEngine { _, _ ->
            when (localEngine.receivedConversations.size) {
                1 -> listOf(
                    // Missing required "level"
                    ChatStreamEvent.ToolCallRequested("set_brightness", """{}""", id = "c-bad"),
                    ChatStreamEvent.Done,
                )
                2 -> listOf(
                    // Retries with valid arguments
                    ChatStreamEvent.ToolCallRequested("set_brightness", """{"level":50}""", id = "c-good"),
                    ChatStreamEvent.Done,
                )
                3 -> listOf(
                    ChatStreamEvent.TokenDelta("Brightness set to 50%."),
                    ChatStreamEvent.Done,
                )
                else -> listOf(ChatStreamEvent.Done)
            }
        }

        val provider = createLocalProvider(toolCapableSpec, localEngine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(allow = true),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Set brightness")),
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolRejected && it.name == "set_brightness" })
        assertTrue(events.any { it is AgentEvent.ToolExecuted && it.name == "set_brightness" && it.success })
        assertEquals(1, tool.executions)
        assertEquals("""{"level":50}""", tool.lastArgs)
        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("Brightness set to 50%.", finalAnswer.text)
    }

    @Test
    fun `7 Tool execution failure is reported back and model handles it cleanly`() = runTest {
        val failingTool = FakeTool(
            name = "failing_sensor",
            tier = PermissionTier.READ_ONLY,
            behavior = { ToolResult(success = false, observationText = "Sensor unavailable", error = "Hardware timeout") },
        )
        val registry = ToolRegistry().apply { register(failingTool) }
        val audit = RecordingAudit()

        lateinit var localEngine: ScriptableLocalEngine
        localEngine = ScriptableLocalEngine { _, _ ->
            if (localEngine.receivedConversations.size == 1) {
                listOf(
                    ChatStreamEvent.ToolCallRequested("failing_sensor", "{}", id = "c-fail"),
                    ChatStreamEvent.Done,
                )
            } else {
                listOf(
                    ChatStreamEvent.TokenDelta("The sensor could not be reached right now."),
                    ChatStreamEvent.Done,
                )
            }
        }

        val provider = createLocalProvider(toolCapableSpec, localEngine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Read sensor")),
            ),
        ).toList()

        val executed = events.filterIsInstance<AgentEvent.ToolExecuted>().single()
        assertFalse(executed.success)
        val finalAnswer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertEquals("The sensor could not be reached right now.", finalAnswer.text)
    }

    @Test
    fun `8 Confirmation-required sensitive tool requires confirmation before executing`() = runTest {
        val deleteTool = FakeTool("delete_item", PermissionTier.SENSITIVE)
        val registry = ToolRegistry().apply { register(deleteTool) }
        val audit = RecordingAudit()
        val gate = RecordingGate(allow = true)

        lateinit var localEngine: ScriptableLocalEngine
        localEngine = ScriptableLocalEngine { _, _ ->
            if (localEngine.receivedConversations.size == 1) {
                listOf(
                    ChatStreamEvent.ToolCallRequested("delete_item", """{"id":"123"}""", id = "c-del"),
                    ChatStreamEvent.Done,
                )
            } else {
                listOf(
                    ChatStreamEvent.TokenDelta("Item 123 deleted."),
                    ChatStreamEvent.Done,
                )
            }
        }

        val provider = createLocalProvider(toolCapableSpec, localEngine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = gate,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Delete item 123")),
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.ConfirmationRequired && it.name == "delete_item" })
        assertEquals(1, deleteTool.executions)
        assertEquals(1, audit.records.size)
        assertTrue(audit.records[0].userConfirmed)
    }

    @Test
    fun `9 Confirmation denied cancels tool execution and records cancelled audit`() = runTest {
        val wipeTool = FakeTool("wipe_data", PermissionTier.SENSITIVE)
        val registry = ToolRegistry().apply { register(wipeTool) }
        val audit = RecordingAudit()
        val gate = RecordingGate(allow = false) // Denied

        val engine = ScriptableLocalEngine { _, _ ->
            listOf(
                ChatStreamEvent.ToolCallRequested("wipe_data", """{}""", id = "c-wipe"),
                ChatStreamEvent.Done,
            )
        }

        val provider = createLocalProvider(toolCapableSpec, engine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = gate,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Wipe everything")),
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.ConfirmationRequired && it.name == "wipe_data" })
        assertTrue(events.any { it is AgentEvent.ToolCancelled && it.name == "wipe_data" })
        assertEquals(0, wipeTool.executions)
        assertEquals(1, audit.records.size)
        assertEquals("cancelled", audit.records[0].resultStatus)
    }

    @Test
    fun `10 Cancellation during execution aborts runner flow safely`() = runTest {
        val started = CompletableDeferred<Unit>()
        val slowTool = object : Tool {
            override val name = "slow_local_op"
            override val description = "slow op"
            override val parametersSchemaJson = """{}"""
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                started.complete(Unit)
                delay(10_000)
                return ToolResult(true, "done")
            }
        }

        val registry = ToolRegistry().apply { register(slowTool) }
        val audit = RecordingAudit()
        val engine = ScriptableLocalEngine { _, _ ->
            listOf(
                ChatStreamEvent.ToolCallRequested("slow_local_op", "{}", id = "c-slow"),
                ChatStreamEvent.Done,
            )
        }
        val provider = createLocalProvider(toolCapableSpec, engine)
        val runner = AgentRunner(registry = registry, audit = audit, confirmationGate = RecordingGate())

        val job = launch {
            runner.run(
                AgentRunRequest(
                    provider = provider,
                    modelId = toolCapableSpec.id,
                    messages = listOf(userRequest("Run slow op")),
                ),
            ).toList()
        }

        started.await()
        job.cancel()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `11 Step limit stops agent loop when model calls tools infinitely`() = runTest {
        val loopTool = FakeTool("loop_tool", PermissionTier.READ_ONLY)
        val registry = ToolRegistry().apply { register(loopTool) }
        val audit = RecordingAudit()

        val engine = ScriptableLocalEngine { _, _ ->
            listOf(
                ChatStreamEvent.ToolCallRequested("loop_tool", "{}", id = "c-loop"),
                ChatStreamEvent.Done,
            )
        }

        val provider = createLocalProvider(toolCapableSpec, engine)
        val runner = AgentRunner(
            registry = registry,
            audit = audit,
            confirmationGate = RecordingGate(),
            stepCap = 4,
        )

        val events = runner.run(
            AgentRunRequest(
                provider = provider,
                modelId = toolCapableSpec.id,
                messages = listOf(userRequest("Loop")),
            ),
        ).toList()

        assertEquals(4, loopTool.executions)
        assertTrue(events.any { it is AgentEvent.StepCapReached && it.stepsUsed == 4 })
    }

    @Test
    fun `12 Chat-only local model provider does not advertise tools capability`() = runTest {
        val engine = ScriptableLocalEngine { _, _ ->
            listOf(ChatStreamEvent.TokenDelta("Chat response"), ChatStreamEvent.Done)
        }
        val provider = createLocalProvider(chatOnlySpec, engine)

        assertFalse(provider.capabilities.supportsTools)
        assertEquals("qwen3-0.6b", provider.id.removePrefix("local-"))
    }
}
