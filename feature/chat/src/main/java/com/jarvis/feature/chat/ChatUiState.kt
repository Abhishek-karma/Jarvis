package com.jarvis.feature.chat

import com.jarvis.core.common.DEFAULT_CONVERSATION_TITLE
import com.jarvis.core.common.Message
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.common.ThinkMode

data class ChatUiState(
    val conversationId: String? = null,
    val conversationTitle: String = DEFAULT_CONVERSATION_TITLE,
    val messages: List<Message> = emptyList(),
    val composerText: String = "",
    /** Initial conversation load — suppresses the empty-state flash while reading Room. */
    val isLoadingConversation: Boolean = true,
    val isStreaming: Boolean = false,
    /** Turn preparation (model resolution / engine load) before the stream starts. */
    val isPreparingSend: Boolean = false,
    val isSendingEnabled: Boolean = true,
    /** Per-chat routing override (Auto / Local / Cloud). Persists with the conversation. */
    val routingOverride: RoutingOverride = RoutingOverride.AUTO,
    /** Effective route for the current response, shown on the route badge. */
    val activeRoute: RoutingOverride = RoutingOverride.CLOUD,

    val routeBadge: RouteBadge? = null,
    /** Voice recording state. */
    val isRecording: Boolean = false,
    /** Transcribing audio to text. */
    val isTranscribing: Boolean = false,

    val playingAudioMessageId: String? = null,
    /** A ReAct agent run is in progress. */
    val isAgentRunning: Boolean = false,
    /** Sensitive-tier tool awaiting an explicit user decision. */
    val pendingConfirmation: AgentConfirmation? = null,
    /** Live step log rendered as the transcript's in-flight tail during an agent run. */
    val agentSteps: List<AgentStep> = emptyList(),
    /** Reasoning-effort setting; the composer pill cycles OFF → AUTO → ON. */
    val thinkMode: ThinkMode = ThinkMode.AUTO,
)

data class RouteBadge(
    val route: RoutingOverride,
    val label: String,
)

/** A Sensitive-tier tool call parked until the user taps Allow/Deny. */
data class AgentConfirmation(
    val toolName: String,
    val argsJson: String,
)

enum class AgentStepState { RUNNING, DONE, FAILED }

/** One row in the agent step list: bold title, optional observation detail. */
data class AgentStep(
    val text: String,
    val state: AgentStepState = AgentStepState.RUNNING,
    /** Secondary observation line under the title (e.g. a tool's result summary). Null hides the line. */
    val detail: String? = null,
    /** Measured wall-clock for a finished step ("1.4s"). Null falls back to the state label. */
    val durationLabel: String? = null,
    /** 0..1 fraction for the running row's progress bar. Null renders indeterminate. */
    val progress: Float? = null,
)
