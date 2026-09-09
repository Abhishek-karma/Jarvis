package com.jarvis.core.network

import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.RoutingOverride

/**
 * High-level user-facing model profiles that abstract raw model IDs and technical complexity.
 */
enum class ModelProfile(
    val displayName: String,
    val description: String,
    val preferredRoute: RoutingOverride,
) {
    FAST(
        displayName = "Fast",
        description = "Optimized for speed, snappy quick answers, and low latency.",
        preferredRoute = RoutingOverride.AUTO,
    ),
    BALANCED(
        displayName = "Balanced",
        description = "Standard balance of conversational quality, depth, and speed.",
        preferredRoute = RoutingOverride.AUTO,
    ),
    REASONING(
        displayName = "Reasoning",
        description = "Deep thought, chain-of-thought analysis, and complex problem solving.",
        preferredRoute = RoutingOverride.CLOUD,
    ),
    PRIVATE(
        displayName = "Private",
        description = "Strictly on-device execution. Personal data never leaves the device.",
        preferredRoute = RoutingOverride.LOCAL,
    ),
    CODING(
        displayName = "Coding",
        description = "Technical precision for software development, debugging, and scripts.",
        preferredRoute = RoutingOverride.AUTO,
    ),
    VISION(
        displayName = "Vision",
        description = "Multimodal analysis for images, photos, and visual documents.",
        preferredRoute = RoutingOverride.CLOUD,
    );

    companion object {
        fun fromName(name: String?): ModelProfile = entries.firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: BALANCED
    }
}

/**
 * Resolves a [ModelProfile] to the most appropriate provider and model from available options.
 */
object ModelProfileResolver {

    /**
     * Resolves the best matching [LlmProvider] adapter for the profile.
     */
    fun resolveAdapter(
        profile: ModelProfile,
        availableAdapters: List<LlmProvider>,
        healthTracker: ProviderHealthTracker? = null,
    ): LlmProvider? {
        if (availableAdapters.isEmpty()) return null

        val healthy = if (healthTracker != null) {
            availableAdapters.filter { healthTracker.isHealthy(it.id) }
        } else {
            availableAdapters
        }
        val pool = healthy.ifEmpty { availableAdapters }

        return when (profile) {
            ModelProfile.VISION -> pool.firstOrNull { it.capabilities.vision } ?: pool.firstOrNull()
            ModelProfile.REASONING -> pool.firstOrNull { it.capabilities.supportsReasoning } ?: pool.firstOrNull()
            ModelProfile.CODING -> pool.firstOrNull { it.capabilities.supportsReasoning || it.capabilities.supportsTools } ?: pool.firstOrNull()
            ModelProfile.FAST -> {
                if (healthTracker != null) {
                    pool.minByOrNull { healthTracker.getHealth(it.id).averageLatencyMs } ?: pool.first()
                } else {
                    pool.first()
                }
            }
            ModelProfile.PRIVATE, ModelProfile.BALANCED -> pool.firstOrNull()
        }
    }

    /**
     * Finds the best matching provider config for the given profile among healthy providers.
     */
    fun resolveProvider(
        profile: ModelProfile,
        availableProviders: List<ProviderConfig>,
        healthTracker: ProviderHealthTracker? = null,
        capabilitiesResolver: (ProviderConfig) -> ProviderCapabilities = { ProviderCapabilities() },
    ): ProviderConfig? {
        if (availableProviders.isEmpty()) return null

        val healthy = if (healthTracker != null) {
            healthTracker.filterHealthy(availableProviders)
        } else {
            availableProviders
        }
        val pool = healthy.ifEmpty { availableProviders }

        return when (profile) {
            ModelProfile.VISION -> pool.firstOrNull { capabilitiesResolver(it).vision } ?: pool.firstOrNull()
            ModelProfile.REASONING -> pool.firstOrNull { capabilitiesResolver(it).supportsReasoning } ?: pool.firstOrNull()
            ModelProfile.CODING -> pool.firstOrNull {
                val cap = capabilitiesResolver(it)
                cap.supportsReasoning || cap.supportsTools
            } ?: pool.firstOrNull()
            ModelProfile.FAST -> {
                if (healthTracker != null) {
                    pool.minByOrNull { healthTracker.getHealth(it.id).averageLatencyMs } ?: pool.first()
                } else {
                    pool.first()
                }
            }
            ModelProfile.PRIVATE, ModelProfile.BALANCED -> pool.firstOrNull()
        }
    }
}
