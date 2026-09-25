package com.jarvis.core.agent.execution

/**
 * Structured canonical execution result preserving status, error code,
 * technical detail, user-facing messaging, and actionable recovery steps.
 */
data class ExecutionResult(
    val status: ExecutionStatus,
    val code: ErrorCode? = null,
    val message: String,
    val userMessage: String,
    val retryable: Boolean = false,
    val recoverable: Boolean = false,
    val requiresUserAction: Boolean = false,
    val suggestedAction: String? = null,
    val actionIntent: String? = null,
    val details: Map<String, Any?> = emptyMap(),
    val severity: ErrorSeverity = ErrorSeverity.INFO,
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() =
        status == ExecutionStatus.SUCCESS || status == ExecutionStatus.PARTIAL_SUCCESS

    val isCancelled: Boolean get() =
        status == ExecutionStatus.CANCELLED || code == ErrorCode.USER_CANCELLED

    val userFacingState: UserFacingState get() {
        if (status == ExecutionStatus.SUCCESS) {
            return UserFacingState(
                type = UserFacingStateType.SUCCESS,
                title = "Success",
                message = userMessage,
                suggestedAction = null,
                actionIntent = null,
                errorCode = null,
                requiresUserAction = false,
            )
        }
        if (status == ExecutionStatus.PARTIAL_SUCCESS) {
            return UserFacingState(
                type = UserFacingStateType.PARTIAL,
                title = "Partially Completed",
                message = userMessage,
                suggestedAction = suggestedAction,
                actionIntent = actionIntent,
                errorCode = code,
                requiresUserAction = requiresUserAction,
            )
        }
        if (code != null) {
            val mapped = ExecutionErrorMapper.map(code, message, details)
            return mapped.copy(
                message = userMessage.ifBlank { mapped.message },
                suggestedAction = suggestedAction ?: mapped.suggestedAction,
                actionIntent = actionIntent ?: mapped.actionIntent,
            )
        }
        return UserFacingState(
            type = UserFacingStateType.FAILED,
            title = "Failed",
            message = userMessage.ifBlank { "Couldn't complete that action." },
            suggestedAction = suggestedAction,
            actionIntent = actionIntent,
            errorCode = code,
            requiresUserAction = requiresUserAction,
        )
    }

    companion object {
        fun success(
            message: String,
            userMessage: String = "",
            details: Map<String, Any?> = emptyMap(),
            completedSteps: List<String> = emptyList(),
        ): ExecutionResult = ExecutionResult(
            status = ExecutionStatus.SUCCESS,
            code = null,
            message = message,
            userMessage = userMessage.ifBlank { message },
            retryable = false,
            recoverable = false,
            requiresUserAction = false,
            suggestedAction = null,
            details = details,
            severity = ErrorSeverity.INFO,
            completedSteps = completedSteps,
        )

        fun partialSuccess(
            message: String,
            userMessage: String,
            code: ErrorCode? = null,
            completedSteps: List<String> = emptyList(),
            pendingSteps: List<String> = emptyList(),
            suggestedAction: String? = null,
            actionIntent: String? = null,
            details: Map<String, Any?> = emptyMap(),
        ): ExecutionResult = ExecutionResult(
            status = ExecutionStatus.PARTIAL_SUCCESS,
            code = code,
            message = message,
            userMessage = userMessage,
            retryable = code?.isRetryable ?: false,
            recoverable = true,
            requiresUserAction = code?.requiresUserAction ?: false,
            suggestedAction = suggestedAction,
            actionIntent = actionIntent,
            details = details,
            severity = ErrorSeverity.WARNING,
            completedSteps = completedSteps,
            pendingSteps = pendingSteps,
        )

        fun failure(
            code: ErrorCode,
            message: String,
            userMessage: String? = null,
            suggestedAction: String? = null,
            actionIntent: String? = null,
            details: Map<String, Any?> = emptyMap(),
            severity: ErrorSeverity? = null,
            completedSteps: List<String> = emptyList(),
            pendingSteps: List<String> = emptyList(),
        ): ExecutionResult {
            val effSeverity = severity ?: code.defaultSeverity
            val mapped = ExecutionErrorMapper.map(code, message, details)
            return ExecutionResult(
                status = mapCodeToStatus(code),
                code = code,
                message = message,
                userMessage = userMessage ?: mapped.message,
                retryable = code.isRetryable,
                recoverable = effSeverity == ErrorSeverity.RECOVERABLE || code.isRetryable,
                requiresUserAction = code.requiresUserAction,
                suggestedAction = suggestedAction ?: mapped.suggestedAction,
                actionIntent = actionIntent ?: mapped.actionIntent,
                details = details,
                severity = effSeverity,
                completedSteps = completedSteps,
                pendingSteps = pendingSteps,
            )
        }

        private fun mapCodeToStatus(code: ErrorCode): ExecutionStatus = when (code) {
            ErrorCode.USER_CANCELLED -> ExecutionStatus.CANCELLED
            ErrorCode.CONFIRMATION_REQUIRED -> ExecutionStatus.NEEDS_CONFIRMATION
            ErrorCode.PERMISSION_REQUIRED -> ExecutionStatus.PERMISSION_REQUIRED
            ErrorCode.ACCESSIBILITY_UNAVAILABLE, ErrorCode.BRIDGE_UNAVAILABLE -> ExecutionStatus.UNAVAILABLE
            ErrorCode.APP_NOT_INSTALLED, ErrorCode.TARGET_NOT_FOUND, ErrorCode.TOOL_NOT_FOUND -> ExecutionStatus.NOT_FOUND
            ErrorCode.APP_NOT_READY, ErrorCode.ACTION_FAILED, ErrorCode.TEXT_INPUT_FAILED,
            ErrorCode.SCROLL_FAILED, ErrorCode.VERIFICATION_FAILED -> ExecutionStatus.AUTOMATION_ERROR
            ErrorCode.AMBIGUOUS_TARGET -> ExecutionStatus.AMBIGUOUS
            ErrorCode.TARGET_DISABLED, ErrorCode.ACTION_REJECTED, ErrorCode.POLICY_DENIED,
            ErrorCode.SENSITIVE_ACTION_BLOCKED, ErrorCode.TOOL_DISABLED, ErrorCode.OPERATION_UNCERTAIN -> ExecutionStatus.BLOCKED
            ErrorCode.DUPLICATE_OPERATION -> ExecutionStatus.SUCCESS
            ErrorCode.TIMEOUT -> ExecutionStatus.TIMEOUT
            ErrorCode.STEP_LIMIT_REACHED, ErrorCode.LOOP_DETECTED -> ExecutionStatus.STEP_LIMIT
            ErrorCode.NETWORK_UNAVAILABLE, ErrorCode.NETWORK_TIMEOUT -> ExecutionStatus.NETWORK_ERROR
            ErrorCode.PROVIDER_ERROR, ErrorCode.MODEL_ERROR, ErrorCode.INVALID_MODEL_RESPONSE -> ExecutionStatus.MODEL_ERROR
            ErrorCode.INVALID_TOOL_ARGUMENTS -> ExecutionStatus.INVALID_INPUT
            ErrorCode.TOOL_EXECUTION_FAILED -> ExecutionStatus.TOOL_ERROR
            ErrorCode.UNKNOWN_ERROR -> ExecutionStatus.UNKNOWN_ERROR
        }
    }
}
