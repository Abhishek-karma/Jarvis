package com.jarvis.core.network

import com.jarvis.core.common.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Health statistics and state for an individual provider/model endpoint.
 */
data class ProviderHealth(
    val providerId: String,
    val isHealthy: Boolean = true,
    val lastSuccessTimestamp: Long? = null,
    val lastFailureTimestamp: Long? = null,
    val averageLatencyMs: Long = 0L,
    val totalRequests: Long = 0L,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val backoffUntilTimestamp: Long? = null,
    val isRateLimited: Boolean = false,
    val rateLimitResetTimestamp: Long? = null,
    val lastErrorKind: ProviderErrorKind? = null,
    val lastErrorMessage: String? = null,
) {
    /** Whether backoff or rate limiting is currently in effect at the given time. */
    fun isTemporarilyUnavailable(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        if (backoffUntilTimestamp != null && currentTimeMs < backoffUntilTimestamp) return true
        if (rateLimitResetTimestamp != null && currentTimeMs < rateLimitResetTimestamp) return true
        return !isHealthy
    }
}

/**
 * Tracks provider and model health signals to enable intelligent routing,
 * automatic backoff on errors, and self-healing recovery.
 *
 * Startup does NOT depend on live health probes; health is updated dynamically
 * based on actual request outcomes.
 */
@Singleton
class ProviderHealthTracker @Inject constructor() {

    private val _healthMap = MutableStateFlow<Map<String, ProviderHealth>>(emptyMap())
    val healthMap: StateFlow<Map<String, ProviderHealth>> = _healthMap.asStateFlow()

    /**
     * Record a successful request to update latency stats and clear consecutive failure backoffs.
     */
    fun recordSuccess(providerId: String, latencyMs: Long) {
        val now = System.currentTimeMillis()
        _healthMap.update { current ->
            val existing = current[providerId] ?: ProviderHealth(providerId = providerId)
            val newTotal = existing.totalRequests + 1
            // Exponential moving average for latency
            val newAvgLatency = if (existing.totalRequests == 0L) {
                latencyMs
            } else {
                ((existing.averageLatencyMs * 4) + latencyMs) / 5
            }

            val updated = existing.copy(
                isHealthy = true,
                lastSuccessTimestamp = now,
                averageLatencyMs = newAvgLatency,
                totalRequests = newTotal,
                consecutiveFailures = 0,
                backoffUntilTimestamp = null,
                isRateLimited = false,
                rateLimitResetTimestamp = null,
                lastErrorKind = null,
                lastErrorMessage = null,
            )
            current + (providerId to updated)
        }
    }

    /**
     * Record a failed request to trigger temporary backoff and track error patterns.
     */
    fun recordFailure(
        providerId: String,
        errorKind: ProviderErrorKind,
        errorMessage: String? = null,
        backoffMs: Long = calculateBackoffMs(errorKind),
    ) {
        val now = System.currentTimeMillis()
        _healthMap.update { current ->
            val existing = current[providerId] ?: ProviderHealth(providerId = providerId)
            val consecutive = existing.consecutiveFailures + 1
            val isRateLimit = errorKind == ProviderErrorKind.RATE_LIMIT

            // Exponential backoff multiplier based on consecutive failures (capped at 5 min)
            val multiplier = minOf(consecutive, 5)
            val actualBackoff = if (isRateLimit) backoffMs else backoffMs * multiplier
            val backoffUntil = now + actualBackoff

            val updated = existing.copy(
                isHealthy = consecutive < 3 && !isRateLimit,
                lastFailureTimestamp = now,
                failureCount = existing.failureCount + 1,
                consecutiveFailures = consecutive,
                backoffUntilTimestamp = backoffUntil,
                isRateLimited = isRateLimit,
                rateLimitResetTimestamp = if (isRateLimit) backoffUntil else existing.rateLimitResetTimestamp,
                lastErrorKind = errorKind,
                lastErrorMessage = errorMessage,
            )
            current + (providerId to updated)
        }
    }

    /**
     * Checks if a provider is currently considered healthy and available for routing.
     */
    fun isHealthy(providerId: String, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val health = _healthMap.value[providerId] ?: return true
        return !health.isTemporarilyUnavailable(currentTimeMs)
    }

    /**
     * Retrieve current health report for a specific provider.
     */
    fun getHealth(providerId: String): ProviderHealth {
        return _healthMap.value[providerId] ?: ProviderHealth(providerId = providerId)
    }

    /**
     * Filter a list of providers to only those currently healthy and not in backoff.
     */
    fun filterHealthy(
        providers: List<ProviderConfig>,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): List<ProviderConfig> {
        val available = providers.filter { isHealthy(it.id, currentTimeMs) }
        // If all providers are in backoff, return the one with the earliest expiring backoff
        if (available.isEmpty() && providers.isNotEmpty()) {
            return listOf(
                providers.minByOrNull { provider ->
                    _healthMap.value[provider.id]?.backoffUntilTimestamp ?: Long.MAX_VALUE
                } ?: providers.first()
            )
        }
        return available
    }

    /**
     * Reset health tracking for a provider (e.g. after user reconfigures credentials).
     */
    fun resetHealth(providerId: String) {
        _healthMap.update { it - providerId }
    }

    companion object {
        fun calculateBackoffMs(kind: ProviderErrorKind): Long = when (kind) {
            ProviderErrorKind.RATE_LIMIT -> 60_000L // 1 minute
            ProviderErrorKind.TIMEOUT -> 15_000L // 15 seconds
            ProviderErrorKind.PROVIDER_UNAVAILABLE -> 30_000L // 30 seconds
            ProviderErrorKind.NETWORK -> 10_000L // 10 seconds
            ProviderErrorKind.AUTHENTICATION -> 300_000L // 5 minutes until reconfigured
            else -> 20_000L // 20 seconds
        }
    }
}
