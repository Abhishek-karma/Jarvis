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
}

/** One routing decision: the route to use plus why. */
data class RoutingDecision(
    val route: RoutingOverride,
    val reason: RoutingReason,
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


    fun classify(
        message: String,
        override: RoutingOverride,
        localModelReady: Boolean,
        isOnline: Boolean,
    ): RoutingDecision =
        when {

            override == RoutingOverride.LOCAL ->
                if (localModelReady) {
                    RoutingDecision(RoutingOverride.LOCAL, RoutingReason.FORCED_LOCAL)
                } else {
                    RoutingDecision(RoutingOverride.CLOUD, RoutingReason.FORCED_LOCAL_FALLBACK)
                }


            override == RoutingOverride.CLOUD ->
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.FORCED_CLOUD)


            override == RoutingOverride.AUTO && localModelReady && matchesAny(message, privacyPatterns) ->
                RoutingDecision(RoutingOverride.LOCAL, RoutingReason.PRIVACY_LOCAL)


            override == RoutingOverride.AUTO && matchesAny(message, realtimePatterns) ->
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.REALTIME_CLOUD)


            override == RoutingOverride.AUTO && matchesAny(message, heavyPatterns) ->
                RoutingDecision(RoutingOverride.CLOUD, RoutingReason.HEAVY_GENERATIVE_CLOUD)


            override == RoutingOverride.AUTO && localModelReady -> {

                if (message.length > 1_000) {
                    RoutingDecision(RoutingOverride.CLOUD, RoutingReason.HEAVY_GENERATIVE_CLOUD)
                } else {
                    RoutingDecision(RoutingOverride.LOCAL, RoutingReason.LIGHT_LOCAL)
                }
            }


            else -> RoutingDecision(RoutingOverride.CLOUD, RoutingReason.DEFAULT_CLOUD)
        }

    private fun matchesAny(
        message: String,
        patterns: List<Regex>,
    ): Boolean = patterns.any { it.containsMatchIn(message) }
}
