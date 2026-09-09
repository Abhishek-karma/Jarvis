package com.jarvis.feature.chat

import com.jarvis.core.common.RoutingOverride


enum class RoutingReason {
    /** The user pinned Local and the on-device model is installed and ready. */
    FORCED_LOCAL,

    /** The user pinned Local but the model isn't ready — fell back to cloud. */
    FORCED_LOCAL_FALLBACK,

    /** The user pinned Cloud. */
    FORCED_CLOUD,

    /** Auto: the message talks about private/personal data — keep it on-device. */
    PRIVACY_LOCAL,

    /** Auto: the message needs fresh, real-time knowledge only the cloud has. */
    REALTIME_CLOUD,

    /** Auto: the request is heavy-generative (long-form output) — offload to cloud. */
    HEAVY_GENERATIVE_CLOUD,

    /** Auto: light request and the on-device model is ready — keep it local. */
    LIGHT_LOCAL,

    /** Auto: no local model ready — cloud by default. */
    DEFAULT_CLOUD,

    /** Auto: attachments/photos require vision model. */
    VISION_CLOUD,

    /** Auto: context size exceeds on-device model capacity. */
    CONTEXT_LIMIT_CLOUD,

    /** Auto: user selected private model profile. */
    PROFILE_PRIVATE_LOCAL,
}

/** One routing decision: the route to use, why, human explanation, and contributing factors. */
data class RoutingDecision(
    val route: RoutingOverride,
    val reason: RoutingReason,
) {
    var explanation: String = reason.name
    var factors: Map<String, String> = emptyMap()

    constructor(
        route: RoutingOverride,
        reason: RoutingReason,
        explanation: String,
        factors: Map<String, String> = emptyMap(),
    ) : this(route, reason) {
        this.explanation = explanation
        this.factors = factors
    }
}

/**
 * Environmental and contextual inputs used for Smart Routing v2 decisions.
 */
data class RoutingContext(
    val message: String,
    val override: RoutingOverride = RoutingOverride.AUTO,
    val localModelReady: Boolean = false,
    val isOnline: Boolean = true,
    val hasAttachments: Boolean = false,
    val requiresTools: Boolean = false,
    val requiresReasoning: Boolean = false,
    val estimatedTokens: Int = 0,
    val isSensitive: Boolean = false,
    val modelProfile: String = "balanced",
    val localContextLimit: Int = 4096,
    val hasHealthyCloudProviders: Boolean = true,
)

object RoutingClassifier {
    private val privacyPatterns =
        listOf(
            Regex("\\bmy (password|ssn|social security|address|phone|passport|license)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(password|ssn|passport|bank account|credit card)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(private|personal) (info|information|data|details)\\b", RegexOption.IGNORE_CASE),
        )

    private val realtimePatterns =
        listOf(
            Regex("\\bwhat(?:'s| is) the (?:current |today'?s? )?(time|weather|temperature)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(weather|forecast|news|stock price|temperature)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(today|right now|currently|latest)\\b", RegexOption.IGNORE_CASE),
        )

    private val heavyPatterns =
        listOf(
            Regex(
                "\\b(?:write|compose|draft)\\b.*\\b(?:\\d+[- ]?(?:word|page)s?|(?:very )?long|detailed|comprehensive|in[- ]depth)\\b",
                RegexOption.IGNORE_CASE,
            ),
        )

    /**
     * Backward-compatible simple routing classifier.
     */
    fun classify(
        message: String,
        override: RoutingOverride,
        localModelReady: Boolean,
        isOnline: Boolean,
    ): RoutingDecision = classify(
        RoutingContext(
            message = message,
            override = override,
            localModelReady = localModelReady,
            isOnline = isOnline,
        ),
    )

    /**
     * Smart Routing v2: Multi-factor explainable policy considering intent, privacy,
     * vision, context size, provider health, network state, and model profile.
     */
    fun classify(context: RoutingContext): RoutingDecision {
        val factors = mutableMapOf<String, String>()
        factors["online"] = context.isOnline.toString()
        factors["localReady"] = context.localModelReady.toString()
        factors["profile"] = context.modelProfile

        val isSensitive = context.isSensitive || matchesAny(context.message, privacyPatterns)
        if (isSensitive) factors["privacy"] = "sensitive"
        if (context.hasAttachments) factors["attachments"] = "true"
        if (context.estimatedTokens > 0) factors["tokens"] = context.estimatedTokens.toString()

        // 1. Explicit user override takes immediate precedence
        if (context.override == RoutingOverride.LOCAL) {
            return if (context.localModelReady) {
                RoutingDecision(
                    route = RoutingOverride.LOCAL,
                    reason = RoutingReason.FORCED_LOCAL,
                    explanation = "Pinned to on-device model by user override.",
                    factors = factors,
                )
            } else {
                RoutingDecision(
                    route = RoutingOverride.CLOUD,
                    reason = RoutingReason.FORCED_LOCAL_FALLBACK,
                    explanation = "Pinned to Local, but no on-device model is installed; falling back to Cloud.",
                    factors = factors,
                )
            }
        }

        if (context.override == RoutingOverride.CLOUD) {
            return RoutingDecision(
                route = RoutingOverride.CLOUD,
                reason = RoutingReason.FORCED_CLOUD,
                explanation = "Pinned to Cloud by user override.",
                factors = factors,
            )
        }

        // 2. Private profile enforcement
        if (context.modelProfile.equals("private", ignoreCase = true) && context.localModelReady) {
            return RoutingDecision(
                route = RoutingOverride.LOCAL,
                reason = RoutingReason.PROFILE_PRIVATE_LOCAL,
                explanation = "Private profile active: strictly routing on-device.",
                factors = factors,
            )
        }

        // 3. Privacy sensitivity in Auto mode
        if (isSensitive && context.localModelReady) {
            return RoutingDecision(
                route = RoutingOverride.LOCAL,
                reason = RoutingReason.PRIVACY_LOCAL,
                explanation = "Sensitive personal data detected; executing on-device to preserve privacy.",
                factors = factors,
            )
        }

        // 4. Vision requirement (attachments present)
        if (context.hasAttachments) {
            return RoutingDecision(
                route = RoutingOverride.CLOUD,
                reason = RoutingReason.VISION_CLOUD,
                explanation = "Request includes image attachments requiring a cloud multimodal model.",
                factors = factors,
            )
        }

        // 5. Real-time dynamic knowledge
        if (matchesAny(context.message, realtimePatterns)) {
            return RoutingDecision(
                route = RoutingOverride.CLOUD,
                reason = RoutingReason.REALTIME_CLOUD,
                explanation = "Message requests real-time external knowledge.",
                factors = factors,
            )
        }

        // 6. Heavy generative or reasoning requirements
        if (matchesAny(context.message, heavyPatterns) || context.message.length > 1_000) {
            return RoutingDecision(
                route = RoutingOverride.CLOUD,
                reason = RoutingReason.HEAVY_GENERATIVE_CLOUD,
                explanation = "Long-form or heavy generation offloaded to cloud.",
                factors = factors,
            )
        }

        // 7. Context length limit
        if (context.estimatedTokens > context.localContextLimit) {
            return RoutingDecision(
                route = RoutingOverride.CLOUD,
                reason = RoutingReason.CONTEXT_LIMIT_CLOUD,
                explanation = "Conversation exceeds on-device model context window (${context.localContextLimit} tokens).",
                factors = factors,
            )
        }

        // 8. Offline fallback
        if (!context.isOnline && context.localModelReady) {
            return RoutingDecision(
                route = RoutingOverride.LOCAL,
                reason = RoutingReason.LIGHT_LOCAL,
                explanation = "Device is offline; routing to available on-device model.",
                factors = factors,
            )
        }

        // 9. Provider backoff: if cloud is down and local is ready, fall back to local
        if (!context.hasHealthyCloudProviders && context.localModelReady) {
            return RoutingDecision(
                route = RoutingOverride.LOCAL,
                reason = RoutingReason.LIGHT_LOCAL,
                explanation = "Cloud providers temporarily unhealthy or in backoff; falling back to on-device.",
                factors = factors,
            )
        }

        // 10. Default Auto behavior
        if (context.localModelReady) {
            return RoutingDecision(
                route = RoutingOverride.LOCAL,
                reason = RoutingReason.LIGHT_LOCAL,
                explanation = "Light request handled quickly and privately on-device.",
                factors = factors,
            )
        }

        return RoutingDecision(
            route = RoutingOverride.CLOUD,
            reason = RoutingReason.DEFAULT_CLOUD,
            explanation = "Default cloud route selected.",
            factors = factors,
        )
    }

    private fun matchesAny(
        message: String,
        patterns: List<Regex>,
    ): Boolean = patterns.any { it.containsMatchIn(message) }
}
