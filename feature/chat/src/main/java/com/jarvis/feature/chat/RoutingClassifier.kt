package com.jarvis.feature.chat

import com.jarvis.core.common.RoutingOverride

/**
 * How a route decision was reached — surfaced in [RoutingDecision.reason] and worth
 * keeping explicit: silent heuristics are indistinguishable from bugs.
 */
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
}

/** One routing decision: the route to use plus why. */
data class RoutingDecision(
    val route: RoutingOverride,
    val reason: RoutingReason,
)

/**
 * Pure smart-routing classifier — the 7-step decision tree from the v0.5 spec
 * (`03-FEATURES §6`). No Android or ViewModel dependencies: fully unit-testable.
 *
 * Rules, in precedence order:
 *  1. [RoutingOverride.LOCAL] pinned → LOCAL if the model is ready, else CLOUD fallback.
 *  2. [RoutingOverride.CLOUD] pinned → CLOUD.
 *  3. AUTO + privacy-sensitive content → LOCAL (never leaves the device).
 *  4. AUTO + real-time query (time/weather/news/stocks, "today/now/current") → CLOUD.
 *  5. AUTO + heavy-generative (long-form ask or long message) → CLOUD.
 *  6. AUTO + light message with the model ready → LOCAL.
 *  7. Otherwise → CLOUD.
 */
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
            // A write/compose/draft ask with an explicit length qualifier: "write a
            // 2000-word essay", "compose a long story", "draft a detailed report".
            // A bare "write a poem" is light — no qualifier, stays on-device.
            Regex(
                "\\b(?:write|compose|draft)\\b.*\\b(?:\\d+[- ]?(?:word|page)s?|(?:very )?long|detailed|comprehensive|in[- ]depth)\\b",
                RegexOption.IGNORE_CASE,
            ),
        )

    /**
     * Classify [message] against the routing override, local-model status, and
     * connectivity. [message] may be empty for a decision that can't see the text yet
     * (e.g. re-resolving on conversation open) — heuristics 3–5 simply don't fire.
     */
    fun classify(
        message: String,
        override: RoutingOverride,
        localModelReady: Boolean,
        isOnline: Boolean,
    ): RoutingDecision =
        when {
            // 1. LOCAL pinned: on-device when ready, cloud fallback otherwise.
            override == RoutingOverride.LOCAL ->
                if (localModelReady) {
                    RoutingDecision(RoutingOverride.LOCAL, RoutingReason.FORCED_LOCAL)
                } else {
                    RoutingDecision(RoutingOverride.CLOUD, RoutingReason.FORCED_LOCAL_FALLBACK)
                }

            // 2. CLOUD pinned.
            override == RoutingOverride.CLOUD ->
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.FORCED_CLOUD)

            // 3. AUTO + privacy-sensitive → keep on-device when possible.
            override == RoutingOverride.AUTO && localModelReady && matchesAny(message, privacyPatterns) ->
                RoutingDecision(RoutingOverride.LOCAL, RoutingReason.PRIVACY_LOCAL)

            // 4. AUTO + real-time question → needs the cloud's fresh knowledge.
            override == RoutingOverride.AUTO && matchesAny(message, realtimePatterns) ->
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.REALTIME_CLOUD)

            // 5. AUTO + heavy-generative → offload the long generation to the cloud.
            override == RoutingOverride.AUTO && matchesAny(message, heavyPatterns) ->
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.HEAVY_GENERATIVE_CLOUD)

            // 6. AUTO + light message, model ready → prefer on-device.
            override == RoutingOverride.AUTO && localModelReady -> {
                // Long free-form text is also heavy even without a trigger phrase.
                if (message.length > 1_000) {
                    RoutingDecision(RoutingOverride.CLOUD, RoutingReason.HEAVY_GENERATIVE_CLOUD)
                } else {
                    RoutingDecision(RoutingOverride.LOCAL, RoutingReason.LIGHT_LOCAL)
                }
            }

            // 7. Default: cloud (no local model ready).
            else -> RoutingDecision(RoutingOverride.CLOUD, RoutingReason.DEFAULT_CLOUD)
        }

    private fun matchesAny(
        message: String,
        patterns: List<Regex>,
    ): Boolean = patterns.any { it.containsMatchIn(message) }
}
