package com.jarvis.core.agent.automation.engine

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.jarvis.core.agent.automation.UiWindowSnapshot
import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale

/**
 * Narrow model boundary for local Android UI action generation.
 * It is intentionally separate from LlmProvider: this model receives UI observations,
 * not user chat history or general reasoning prompts.
 */
fun interface PhoneActionModel {
    suspend fun decide(goal: String, snapshot: UiWindowSnapshot, completedSteps: List<String>): PhoneActionDecision
}

sealed interface PhoneActionDecision {
    data class Action(val action: PhoneAction) : PhoneActionDecision
    data class Unavailable(val reason: String) : PhoneActionDecision
}

sealed interface PhoneAction {
    data class Click(val target: String, val x: Float? = null, val y: Float? = null) : PhoneAction
    data class Type(val text: String, val target: String? = null, val submit: Boolean = false) : PhoneAction
    data class Submit(val target: String? = null) : PhoneAction
    data class Scroll(val direction: String) : PhoneAction
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) : PhoneAction
    data object Back : PhoneAction
    data class Done(val postcondition: String? = null) : PhoneAction
    data class Fail(val reason: String) : PhoneAction
}

/** Safe default until the MobileActions-270M runtime/artifact is installed. */
class UnavailablePhoneActionModel(
    private val reason: String = "MobileActions-270M is not installed on this device.",
) : PhoneActionModel {
    override suspend fun decide(
        goal: String,
        snapshot: UiWindowSnapshot,
        completedSteps: List<String>,
    ): PhoneActionDecision = PhoneActionDecision.Unavailable(reason)
}

/** Strict parser for the structured output emitted by a local MobileActions runtime. */
class JsonPhoneActionModel(
    private val infer: suspend (String) -> String,
) : PhoneActionModel {
    override suspend fun decide(
        goal: String,
        snapshot: UiWindowSnapshot,
        completedSteps: List<String>,
    ): PhoneActionDecision = try {
        val raw = infer(buildPrompt(goal, snapshot, completedSteps)).trim()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            PhoneActionDecision.Unavailable("MobileActions returned no structured action.")
        } else {
            parseAction(JSONObject(raw.substring(start, end + 1)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PhoneActionDecision.Unavailable("MobileActions returned an invalid structured action.")
    }

    internal fun parseResponse(raw: String): PhoneActionDecision {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return PhoneActionDecision.Unavailable("MobileActions returned no structured action.")
        return parseAction(JSONObject(text.substring(start, end + 1)))
    }

    private fun parseAction(json: JSONObject): PhoneActionDecision {
        return when (json.optString("action").lowercase(Locale.US)) {
            "click", "tap" -> json.optString("target").takeIf { it.isNotBlank() }
                ?.let { PhoneActionDecision.Action(PhoneAction.Click(it)) }
                ?: PhoneActionDecision.Unavailable("MobileActions click action had no target.")
            "type" -> json.optString("text").takeIf { it.isNotBlank() }
                ?.let { PhoneActionDecision.Action(PhoneAction.Type(it, json.optString("target").ifBlank { null }, json.optBoolean("submit"))) }
                ?: PhoneActionDecision.Unavailable("MobileActions type action had no text.")
            "submit" -> PhoneActionDecision.Action(PhoneAction.Submit(json.optString("target").ifBlank { null }))
            "scroll" -> PhoneActionDecision.Action(PhoneAction.Scroll(json.optString("direction", "down")))
            "back" -> PhoneActionDecision.Action(PhoneAction.Back)
            "done" -> PhoneActionDecision.Action(PhoneAction.Done(json.optString("postcondition").ifBlank { null }))
            "fail" -> PhoneActionDecision.Action(PhoneAction.Fail(json.optString("reason", "MobileActions could not continue.")))
            else -> PhoneActionDecision.Unavailable("MobileActions returned an unsupported action.")
        }
    }

    internal fun buildPrompt(goal: String, snapshot: UiWindowSnapshot, completedSteps: List<String>): String {
        val elements = snapshot.elements.take(25).joinToString("\n") {
            "[${it.index}] ${it.type} ${it.displayLabel} id=${it.viewId ?: ""} editable=${it.isEditable} clickable=${it.isClickable}"
        }
        return """
            You are MobileActions-270M, a local Android UI action model.
            Return exactly one JSON object. Never explain.
            Allowed actions: click, type, submit, scroll, back, done, fail.
            Schema: {"action":"click","target":"index or visible text"}
            or {"action":"type","text":"...","target":"index","submit":false}
            Goal: $goal
            Package: ${snapshot.packageName}
            UI:
            $elements
            Completed steps:
            ${completedSteps.joinToString("\n")}
        """.trimIndent()
    }
}

/** Real on-device MobileActions runtime backed by Google LiteRT-LM. */
class LiteRtPhoneActionModel(
    private val modelFile: File,
) : PhoneActionModel, AutoCloseable {
    private var engine: Engine? = null

    override suspend fun decide(goal: String, snapshot: UiWindowSnapshot, completedSteps: List<String>): PhoneActionDecision {
        if (!modelFile.isFile || modelFile.length() == 0L) {
            return PhoneActionDecision.Unavailable("MobileActions-270M model is not installed. Expected ${modelFile.absolutePath}.")
        }
        return try {
            val active = synchronized(this) {
                engine ?: Engine(EngineConfig(modelPath = modelFile.absolutePath)).also {
                    it.initialize()
                    engine = it
                }
            }
            active.createConversation().use { conversation ->
                val prompt = JsonPhoneActionModel { error("unused") }.buildPrompt(goal, snapshot, completedSteps)
                val response = conversation.sendMessage(prompt)
                val raw = response.contents.contents.filterIsInstance<Content.Text>().joinToString("\n") { it.text }
                JsonPhoneActionModel { raw }.parseResponse(raw)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PhoneActionDecision.Unavailable("MobileActions-270M inference failed: ${e.message ?: "unknown error"}")
        }
    }

    override fun close() {
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }
}
