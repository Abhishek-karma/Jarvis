package com.jarvis.core.ml

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole

/**
 * Protocol-identity layer between Jarvis conversation history and the native LiteRT-LM
 * message protocol.
 *
 * [toNativeTurn] maps a Jarvis [Message] to a [NativeTurn] — a pure-Kotlin description of
 * the native message. The mapping is the semantic core of same-conversation tool
 * continuation: replaying USER → ASSISTANT(tool call) → TOOL(result) → ASSISTANT(final)
 * as `initialMessages` produces exactly the turn sequence LiteRT-LM itself would have
 * produced by continuing the original conversation in place:
 *  - USER              -> user turn with the text content
 *  - ASSISTANT + tool  -> model turn carrying the recorded tool call (name + parsed args)
 *  - ASSISTANT plain   -> model turn with text
 *  - TOOL              -> tool turn with a response named after the called tool
 *
 * [toNativeTurn] is unit-testable on the JVM; [toNativeMessage] is the thin adapter into
 * the SDK's `Message` factories (one constructor call per turn kind — kept deliberately
 * trivial so the tested mapping is the only logic).
 */
internal object LiteRtMessageCodec {

    /** Pure-Kotlin description of one native LiteRT-LM message turn. */
    internal sealed interface NativeTurn {
        data class User(val text: String) : NativeTurn

        data class System(val text: String) : NativeTurn

        data class Model(
            val text: String?,
            val toolCalls: List<NativeToolCall>,
        ) : NativeTurn

        data class Tool(
            val toolName: String,
            val response: String,
        ) : NativeTurn
    }

    internal data class NativeToolCall(
        val name: String,
        val arguments: Map<String, Any>,
    )

    fun toNativeMessage(message: Message): LiteRtMessage = toNativeTurn(message).toLiteRtMessage()

    internal fun toNativeTurn(message: Message): NativeTurn =
        when (message.role) {
            MessageRole.USER -> NativeTurn.User(message.content)
            MessageRole.SYSTEM -> NativeTurn.System(message.content)
            MessageRole.ASSISTANT -> {
                val callName = message.toolCallName
                if (callName != null) {
                    val argsMap: Map<String, Any> = runCatching {
                        val jsonElem = JsonParser.parseString(message.toolCallArgsJson ?: "{}")
                        if (jsonElem.isJsonObject) {
                            jsonToMap(jsonElem.asJsonObject)
                        } else emptyMap()
                    }.getOrDefault(emptyMap())
                    NativeTurn.Model(
                        text = message.content.ifEmpty { null },
                        toolCalls = listOf(NativeToolCall(name = callName, arguments = argsMap)),
                    )
                } else {
                    NativeTurn.Model(text = message.content, toolCalls = emptyList())
                }
            }
            MessageRole.TOOL -> NativeTurn.Tool(
                toolName = message.toolCallName ?: "tool",
                response = message.content,
            )
        }

    private fun NativeTurn.toLiteRtMessage(): LiteRtMessage =
        when (this) {
            is NativeTurn.User -> LiteRtMessage.user(text)
            is NativeTurn.System -> LiteRtMessage.system(text)
            is NativeTurn.Model -> LiteRtMessage.model(
                contents = Contents.of(Content.Text(text.orEmpty())),
                toolCalls = toolCalls.map { call ->
                    ToolCall(name = call.name, arguments = call.arguments)
                },
                channels = emptyMap(),
            )
            is NativeTurn.Tool -> LiteRtMessage.tool(
                Contents.of(Content.ToolResponse(name = toolName, response = response)),
            )
        }

    internal fun jsonToMap(json: JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for ((key, value) in json.entrySet()) {
            when {
                value.isJsonPrimitive -> {
                    val prim = value.asJsonPrimitive
                    when {
                        prim.isBoolean -> map[key] = prim.asBoolean
                        prim.isNumber -> map[key] = prim.asNumber
                        else -> map[key] = prim.asString
                    }
                }
                value.isJsonObject -> map[key] = jsonToMap(value.asJsonObject)
                value.isJsonArray -> map[key] = value.asJsonArray.toString()
                value.isJsonNull -> Unit
            }
        }
        return map
    }
}
