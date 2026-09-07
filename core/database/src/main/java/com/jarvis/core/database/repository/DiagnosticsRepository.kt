package com.jarvis.core.database.repository

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.database.dao.RequestDiagnosticsDao
import com.jarvis.core.database.entity.RequestDiagnosticsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain model for a single LLM/Agent request diagnostic trace.
 */
data class RequestDiagnostics(
    val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val providerId: String,
    val model: String,
    val route: String,
    val latencyMs: Long,
    val firstTokenLatencyMs: Long? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val retries: Int = 0,
    val fallbacks: List<String> = emptyList(),
    val toolCount: Int = 0,
    val failureClass: String? = null,
    val errorMessage: String? = null,
)

@Singleton
class DiagnosticsRepository @Inject constructor(
    private val dao: RequestDiagnosticsDao,
    private val dispatchers: DispatcherProvider,
) {
    fun observeRecent(limit: Int = 50): Flow<List<RequestDiagnostics>> =
        dao.observeRecent(limit).map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun getRecent(limit: Int = 50): List<RequestDiagnostics> =
        withContext(dispatchers.io) {
            dao.getRecent(limit).map { it.toDomain() }
        }

    suspend fun record(diagnostics: RequestDiagnostics) =
        withContext(dispatchers.io) {
            dao.insert(diagnostics.toEntity())
        }

    suspend fun clearAll() =
        withContext(dispatchers.io) {
            dao.clearAll()
        }

    /**
     * Generates a privacy-safe, sanitized troubleshooting report suitable for export.
     * Never contains raw message bodies, personal memory text, or API credentials.
     */
    suspend fun generateSanitizedReport(): String = withContext(dispatchers.io) {
        val traces = dao.getRecent(50).map { it.toDomain() }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val totalTraces = traces.size
        val failedTraces = traces.count { it.failureClass != null || it.errorMessage != null }
        val avgLatency = if (traces.isNotEmpty()) traces.map { it.latencyMs }.average().toLong() else 0L

        buildString {
            appendLine("=== Jarvis Diagnostics Report ===")
            appendLine("Generated: ${dateFormat.format(Date())}")
            appendLine("Total Traces Analyzed: $totalTraces")
            appendLine("Failures Recorded: $failedTraces")
            appendLine("Average Request Latency: ${avgLatency}ms")
            appendLine()
            appendLine("--- Recent Request Traces ---")
            if (traces.isEmpty()) {
                appendLine("No diagnostics recorded yet.")
            } else {
                traces.forEachIndexed { index, trace ->
                    appendLine("[$index] Request ID: ${trace.requestId}")
                    appendLine("  Time: ${dateFormat.format(Date(trace.timestamp))}")
                    appendLine("  Route: ${trace.route} | Provider: ${trace.providerId} | Model: ${trace.model}")
                    appendLine("  Latency: ${trace.latencyMs}ms (first-token: ${trace.firstTokenLatencyMs?.let { "${it}ms" } ?: "N/A"})")
                    appendLine("  Tokens: prompt=${trace.promptTokens}, completion=${trace.completionTokens}, total=${trace.totalTokens}")
                    appendLine("  Tools Invoked: ${trace.toolCount} | Retries: ${trace.retries}")
                    if (trace.fallbacks.isNotEmpty()) {
                        appendLine("  Fallbacks: ${trace.fallbacks.joinToString(", ")}")
                    }
                    if (trace.failureClass != null || trace.errorMessage != null) {
                        appendLine("  FAILURE: ${trace.failureClass ?: "UNKNOWN"} - ${trace.errorMessage ?: "None"}")
                    }
                    appendLine()
                }
            }
            appendLine("=== End Report (Privacy Sanitized) ===")
        }
    }

    private fun RequestDiagnosticsEntity.toDomain(): RequestDiagnostics =
        RequestDiagnostics(
            id = id,
            requestId = requestId,
            timestamp = timestamp,
            providerId = providerId,
            model = model,
            route = route,
            latencyMs = latencyMs,
            firstTokenLatencyMs = firstTokenLatencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            retries = retries,
            fallbacks = if (fallbacks.isBlank()) emptyList() else fallbacks.split(",").map { it.trim() },
            toolCount = toolCount,
            failureClass = failureClass,
            errorMessage = errorMessage,
        )

    private fun RequestDiagnostics.toEntity(): RequestDiagnosticsEntity =
        RequestDiagnosticsEntity(
            id = id,
            requestId = requestId,
            timestamp = timestamp,
            providerId = providerId,
            model = model,
            route = route,
            latencyMs = latencyMs,
            firstTokenLatencyMs = firstTokenLatencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            retries = retries,
            fallbacks = fallbacks.joinToString(","),
            toolCount = toolCount,
            failureClass = failureClass,
            errorMessage = errorMessage,
        )
}
