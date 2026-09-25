package com.jarvis.core.agent.automation

/**
 * Explicit states of the phone automation lifecycle.
 */
enum class AutomationState {
    IDLE,
    OBSERVING,
    DECIDING,
    ACTING,
    WAITING,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BLOCKED,
    AMBIGUOUS,
}

/**
 * Standard machine-readable error codes for automation actions.
 */
object AutomationErrorCodes {
    const val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    const val NO_ACTIVE_WINDOW = "NO_ACTIVE_WINDOW"
    const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
    const val AMBIGUOUS_TARGET = "AMBIGUOUS_TARGET"
    const val TARGET_DISABLED = "TARGET_DISABLED"
    const val NOT_EDITABLE = "NOT_EDITABLE"
    const val NOT_SCROLLABLE = "NOT_SCROLLABLE"
    const val ACTION_FAILED = "ACTION_FAILED"
    const val VERIFICATION_FAILED = "VERIFICATION_FAILED"
    const val TIMEOUT = "TIMEOUT"
    const val SYSTEM_BLOCKED = "SYSTEM_BLOCKED"
    const val STEP_LIMIT_REACHED = "STEP_LIMIT_REACHED"
    const val LOOP_DETECTED = "LOOP_DETECTED"
    const val CANCELLED = "CANCELLED"
}

/**
 * Machine-readable and structured result of an automation action.
 */
data class AutomationActionResult(
    val success: Boolean,
    val action: String,
    val target: String? = null,
    val method: String? = null,
    val packageName: String? = null,
    val stateChanged: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryable: Boolean = false,
    val updatedSnapshot: UiWindowSnapshot? = null,
    val candidateMatches: List<UiElementSnapshot> = emptyList(),
) {
    fun toObservationText(): String = buildString {
        if (success) {
            appendLine("Action \"$action\" succeeded${if (!target.isNullOrBlank()) " on \"$target\"" else ""}${if (!method.isNullOrBlank()) " via $method" else ""}.")
            if (!packageName.isNullOrBlank()) {
                appendLine("Active package: $packageName")
            }
            if (updatedSnapshot != null) {
                appendLine("\n--- Updated Screen ---")
                appendLine(updatedSnapshot.format())
            }
        } else {
            appendLine("Action \"$action\" failed: ${errorMessage ?: errorCode ?: "Unknown failure"}.")
            if (errorCode == AutomationErrorCodes.AMBIGUOUS_TARGET && candidateMatches.isNotEmpty()) {
                appendLine("Ambiguous target. Multiple candidates match:")
                for (match in candidateMatches) {
                    appendLine(" - [${match.index}] ${match.displayLabel} (id: ${match.viewId ?: "none"})")
                }
                appendLine("Please specify target by index number (e.g. target=\"${candidateMatches.first().index}\").")
            } else if (updatedSnapshot != null) {
                appendLine("\n--- Current Screen ---")
                appendLine(updatedSnapshot.format())
            }
        }
    }.trimEnd()
}
