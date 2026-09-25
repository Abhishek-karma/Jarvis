package com.jarvis.core.agent.needle

/**
 * Configuration for Jarvis's fast on-device capability router.
 *
 * No native/neural Needle model is bundled in this repository. The router is a small deterministic
 * keyword capability router (see [NeedleEngine]); [confidenceThreshold] is the bar below which a
 * match is escalated to the AgentRunner rather than executing directly.
 */
data class NeedleConfig(
    val confidenceThreshold: Float = 0.80f,
)
