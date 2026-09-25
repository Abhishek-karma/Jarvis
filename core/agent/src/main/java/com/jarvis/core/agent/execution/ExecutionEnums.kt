package com.jarvis.core.agent.execution

/**
 * Severity categorization for errors and failures.
 */
enum class ErrorSeverity {
    INFO,
    WARNING,
    RECOVERABLE,
    ACTION_REQUIRED,
    FATAL,
}

/**
 * High-level execution status for an agent run, goal, or tool invocation.
 */
enum class ExecutionStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    BLOCKED,
    CANCELLED,
    TIMEOUT,
    UNAVAILABLE,
    AMBIGUOUS,
    PERMISSION_REQUIRED,
    NEEDS_CONFIRMATION,
    NETWORK_ERROR,
    MODEL_ERROR,
    TOOL_ERROR,
    AUTOMATION_ERROR,
    NOT_FOUND,
    INVALID_INPUT,
    STEP_LIMIT,
    UNKNOWN_ERROR,
}

/**
 * Canonical taxonomy of structured error codes across Jarvis.
 */
enum class ErrorCode(
    val defaultSeverity: ErrorSeverity,
    val isRetryable: Boolean,
    val requiresUserAction: Boolean,
) {
    // User / Confirmation / Security
    USER_CANCELLED(ErrorSeverity.INFO, isRetryable = false, requiresUserAction = false),
    CONFIRMATION_REQUIRED(ErrorSeverity.INFO, isRetryable = false, requiresUserAction = true),
    PERMISSION_REQUIRED(ErrorSeverity.ACTION_REQUIRED, isRetryable = false, requiresUserAction = true),
    ACCESSIBILITY_UNAVAILABLE(ErrorSeverity.ACTION_REQUIRED, isRetryable = false, requiresUserAction = true),

    // App state
    APP_NOT_INSTALLED(ErrorSeverity.ACTION_REQUIRED, isRetryable = false, requiresUserAction = true),
    APP_NOT_READY(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),

    // UI Automation target & action
    TARGET_NOT_FOUND(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),
    AMBIGUOUS_TARGET(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = true),
    TARGET_DISABLED(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),
    ACTION_FAILED(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),
    ACTION_REJECTED(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),

    // Automation primitives
    TEXT_INPUT_FAILED(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),
    SCROLL_FAILED(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),
    VERIFICATION_FAILED(ErrorSeverity.WARNING, isRetryable = true, requiresUserAction = false),

    // Automation & loop control
    TIMEOUT(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),
    STEP_LIMIT_REACHED(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),
    LOOP_DETECTED(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),

    // Network & Provider / LLM
    NETWORK_UNAVAILABLE(ErrorSeverity.ACTION_REQUIRED, isRetryable = true, requiresUserAction = true),
    NETWORK_TIMEOUT(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),
    PROVIDER_ERROR(ErrorSeverity.WARNING, isRetryable = true, requiresUserAction = false),
    MODEL_ERROR(ErrorSeverity.WARNING, isRetryable = true, requiresUserAction = false),
    INVALID_MODEL_RESPONSE(ErrorSeverity.WARNING, isRetryable = true, requiresUserAction = false),

    // Tool registry & validation
    TOOL_NOT_FOUND(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),
    INVALID_TOOL_ARGUMENTS(ErrorSeverity.WARNING, isRetryable = true, requiresUserAction = false),
    TOOL_DISABLED(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),
    TOOL_EXECUTION_FAILED(ErrorSeverity.RECOVERABLE, isRetryable = true, requiresUserAction = false),

    // Security & Policy
    POLICY_DENIED(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),
    SENSITIVE_ACTION_BLOCKED(ErrorSeverity.WARNING, isRetryable = false, requiresUserAction = false),

    // Durable Operations / Idempotency
    OPERATION_UNCERTAIN(ErrorSeverity.ACTION_REQUIRED, isRetryable = false, requiresUserAction = true),
    DUPLICATE_OPERATION(ErrorSeverity.INFO, isRetryable = false, requiresUserAction = false),

    // Privilege bridge
    BRIDGE_UNAVAILABLE(ErrorSeverity.ACTION_REQUIRED, isRetryable = true, requiresUserAction = true),

    // Generic fallback
    UNKNOWN_ERROR(ErrorSeverity.WARNING, isRetryable = true, requiresUserAction = false);

    companion object {
        fun fromString(value: String?): ErrorCode {
            if (value.isNullOrBlank()) return UNKNOWN_ERROR
            return runCatching { valueOf(value.uppercase()) }.getOrElse {
                when (value.lowercase().trim()) {
                    "target_not_found" -> TARGET_NOT_FOUND
                    "ambiguous_target" -> AMBIGUOUS_TARGET
                    "target_disabled" -> TARGET_DISABLED
                    "accessibility_unavailable", "service_unavailable", "accessibility_service_unavailable" -> ACCESSIBILITY_UNAVAILABLE
                    "timeout" -> TIMEOUT
                    "loop_detected" -> LOOP_DETECTED
                    "verification_failed" -> VERIFICATION_FAILED
                    "step_limit_reached", "step_cap_reached" -> STEP_LIMIT_REACHED
                    "policy_denied" -> POLICY_DENIED
                    "sensitive_action_blocked" -> SENSITIVE_ACTION_BLOCKED
                    "user_cancelled", "cancelled", "denied" -> USER_CANCELLED
                    "confirmation_required", "needs_confirmation" -> CONFIRMATION_REQUIRED
                    "permission_required" -> PERMISSION_REQUIRED
                    "app_not_installed" -> APP_NOT_INSTALLED
                    "app_launch_failed", "app_not_ready" -> APP_NOT_READY
                    "network_timeout" -> NETWORK_TIMEOUT
                    "network_unavailable" -> NETWORK_UNAVAILABLE
                    "provider_error" -> PROVIDER_ERROR
                    "model_error" -> MODEL_ERROR
                    "invalid_model_response" -> INVALID_MODEL_RESPONSE
                    "tool_not_found" -> TOOL_NOT_FOUND
                    "tool_disabled" -> TOOL_DISABLED
                    "invalid_arguments", "missing_parameters", "missing_query", "missing_text" -> INVALID_TOOL_ARGUMENTS
                    "tool_execution_failed" -> TOOL_EXECUTION_FAILED
                    "bridge_unavailable", "shizuku_unavailable", "shizuku_not_installed",
                    "shizuku_service_stopped", "shizuku_permission_denied" -> BRIDGE_UNAVAILABLE
                    else -> UNKNOWN_ERROR
                }
            }
        }
    }
}

/**
 * Canonical run state for the agent and UI.
 */
enum class AgentRunState {
    IDLE,
    RUNNING,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isActive: Boolean get() =
        this == RUNNING || this == WAITING_FOR_CONFIRMATION ||
            this == WAITING_FOR_PERMISSION || this == WAITING_FOR_USER
}
