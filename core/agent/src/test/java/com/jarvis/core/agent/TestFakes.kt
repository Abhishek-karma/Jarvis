package com.jarvis.core.agent

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scriptable [Tool] that records how often it ran and with what args. */
class FakeTool(
    override val name: String,
    override val tier: PermissionTier,
    override val description: String = "Fake tool $name",
    override val parametersSchemaJson: String = """{"type":"object","properties":{},"required":[]}""",
    private val behavior: suspend (String) -> ToolResult = { ToolResult(success = true, observationText = "ok") },
) : Tool {
    var executions: Int = 0
    var lastArgs: String? = null

    override suspend fun execute(argsJson: String): ToolResult {
        executions++
        lastArgs = argsJson
        return behavior(argsJson)
    }
}

/** [LlmProvider] whose per-call response is scripted by the test. */
class FakeLlmProvider(
    var script: (ChatRequest) -> List<ChatStreamEvent> = { listOf(ChatStreamEvent.Done) },
    override val capabilities: ProviderCapabilities = ProviderCapabilities(supportsTools = true),
) : LlmProvider {
    override val id: String = "fake"
    val requests = mutableListOf<ChatRequest>()

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> =
        flow {
            requests += request
            script(request).forEach { emit(it) }
        }

    override suspend fun listModels(): Result<List<ModelInfo>> = Result.success(emptyList())

    override fun close() = Unit
}

class RecordingAudit : AuditLogger {
    val records = mutableListOf<AuditRecord>()

    override suspend fun record(entry: AuditRecord) {
        records += entry
    }
}

class RecordingGate(
    var allow: Boolean = true,
) : ConfirmationGate {
    val asked = mutableListOf<Pair<String, String>>()

    override suspend fun confirm(
        toolName: String,
        argsJson: String,
    ): Boolean {
        asked += toolName to argsJson
        return allow
    }
}

fun userRequest(text: String) =
    Message(
        conversationId = "c1",
        role = MessageRole.USER,
        content = text,
    )
