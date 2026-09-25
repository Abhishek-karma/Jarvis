package com.jarvis.core.agent.execution

/**
 * Central mapper from internal errors, error codes, and technical details
 * to concise, actionable [UserFacingState]s.
 */
object ExecutionErrorMapper {

    fun map(
        code: ErrorCode,
        technicalDetail: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ): UserFacingState {
        val targetName = (details["target"] as? String)?.takeIf { it.isNotBlank() }
        val appName = (details["app"] as? String ?: details["appName"] as? String ?: details["package_name"] as? String)?.takeIf { it.isNotBlank() }
        val toolName = (details["tool"] as? String ?: details["toolName"] as? String)?.takeIf { it.isNotBlank() }

        return when (code) {
            ErrorCode.USER_CANCELLED -> UserFacingState(
                type = UserFacingStateType.CANCELLED,
                title = "Cancelled",
                message = "Stopped.",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.CONFIRMATION_REQUIRED -> UserFacingState(
                type = UserFacingStateType.WAITING,
                title = "Confirmation Required",
                message = "Waiting for your confirmation.",
                suggestedAction = "Review and confirm",
                actionIntent = "confirm",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.PERMISSION_REQUIRED -> UserFacingState(
                type = UserFacingStateType.ACTION_REQUIRED,
                title = "Permission Required",
                message = if (appName != null) {
                    "Jarvis needs permission to access $appName."
                } else {
                    "Permission is required to perform this action."
                },
                suggestedAction = "Grant permission",
                actionIntent = "open_settings:permissions",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.ACCESSIBILITY_UNAVAILABLE -> UserFacingState(
                type = UserFacingStateType.ACTION_REQUIRED,
                title = "Accessibility Required",
                message = if (appName != null) {
                    "Jarvis needs Accessibility access to control $appName."
                } else {
                    "Jarvis needs Accessibility access to interact with apps."
                },
                suggestedAction = "Open Accessibility Settings",
                actionIntent = "open_settings:accessibility",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.BRIDGE_UNAVAILABLE -> UserFacingState(
                type = UserFacingStateType.ACTION_REQUIRED,
                title = "Device Bridge Unavailable",
                message = "Advanced device control needs the privilege bridge. Set up Shizuku in Settings to continue.",
                suggestedAction = "Open Settings",
                actionIntent = "open_settings:bridge",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.APP_NOT_INSTALLED -> UserFacingState(
                type = UserFacingStateType.ACTION_REQUIRED,
                title = "App Not Installed",
                message = if (appName != null) {
                    "$appName isn't installed on this phone."
                } else {
                    "The required app is not installed on this device."
                },
                suggestedAction = "Install app",
                actionIntent = "install_app:${appName.orEmpty()}",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.APP_NOT_READY -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "App Not Ready",
                message = if (appName != null) {
                    "$appName did not open or is still loading."
                } else {
                    "The app didn't open."
                },
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.TARGET_NOT_FOUND -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Item Not Found",
                message = if (targetName != null) {
                    "I couldn't find \"$targetName\" on the screen."
                } else {
                    "I couldn't find that control on screen."
                },
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.AMBIGUOUS_TARGET -> UserFacingState(
                type = UserFacingStateType.ACTION_REQUIRED,
                title = "Multiple Matches",
                message = if (targetName != null) {
                    "I found multiple items matching \"$targetName\"."
                } else {
                    "I stopped because multiple matching controls were found."
                },
                suggestedAction = "Please specify which one",
                actionIntent = "clarify",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.TARGET_DISABLED -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Control Disabled",
                message = if (targetName != null) {
                    "\"$targetName\" is currently disabled on screen."
                } else {
                    "That control is currently disabled."
                },
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.ACTION_FAILED, ErrorCode.TEXT_INPUT_FAILED, ErrorCode.SCROLL_FAILED -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Action Failed",
                message = if (code == ErrorCode.TEXT_INPUT_FAILED) {
                    "I couldn't type into the input field."
                } else if (code == ErrorCode.SCROLL_FAILED) {
                    "I couldn't scroll the screen."
                } else {
                    "I couldn't perform that action on the screen."
                },
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.ACTION_REJECTED -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Action Rejected",
                message = "The app rejected the action.",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.VERIFICATION_FAILED -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Verification Failed",
                message = "The expected screen change was not observed.",
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.TIMEOUT -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Timed Out",
                message = if (appName != null) {
                    "I waited for $appName, but it didn't respond in time."
                } else {
                    "The operation timed out."
                },
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.STEP_LIMIT_REACHED -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Step Limit Reached",
                message = "The task reached the maximum number of steps without completing.",
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.LOOP_DETECTED -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Repetition Detected",
                message = "The action was repeating without producing changes on screen.",
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.NETWORK_UNAVAILABLE -> UserFacingState(
                type = UserFacingStateType.ACTION_REQUIRED,
                title = "No Network Connection",
                message = "No internet connection available.",
                suggestedAction = "Check your connection",
                actionIntent = "open_settings:network",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.NETWORK_TIMEOUT -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Network Timeout",
                message = "The network connection timed out.",
                suggestedAction = "Retry",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.PROVIDER_ERROR, ErrorCode.MODEL_ERROR -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "AI Service Issue",
                message = "The AI service encountered an error.",
                suggestedAction = "Retry",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.INVALID_MODEL_RESPONSE -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Model Error",
                message = "Received an unexpected response from the model.",
                suggestedAction = "Retry",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.TOOL_NOT_FOUND -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Feature Unavailable",
                message = if (toolName != null) {
                    "The requested feature \"$toolName\" is not available."
                } else {
                    "The requested feature is not available."
                },
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.INVALID_TOOL_ARGUMENTS -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Invalid Request",
                message = "The request was missing required information or had invalid parameters.",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.TOOL_DISABLED -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Feature Disabled",
                message = if (toolName != null) {
                    "\"$toolName\" is currently disabled in settings."
                } else {
                    "This feature is currently disabled."
                },
                suggestedAction = "Enable in settings",
                actionIntent = "open_settings:tools",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.TOOL_EXECUTION_FAILED -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Operation Failed",
                message = if (toolName != null) {
                    "Failed to execute $toolName."
                } else {
                    "The operation failed."
                },
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.POLICY_DENIED -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Action Not Allowed",
                message = technicalDetail?.takeIf { it.isNotBlank() }
                    ?: "Jarvis isn't allowed to perform that action.",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.SENSITIVE_ACTION_BLOCKED -> UserFacingState(
                type = UserFacingStateType.FAILED,
                title = "Action Blocked",
                message = "Sensitive action was blocked by security policy.",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.OPERATION_UNCERTAIN -> UserFacingState(
                type = UserFacingStateType.ACTION_REQUIRED,
                title = "Uncertain Action State",
                message = "I started the operation, but I can't confirm whether it completed.",
                suggestedAction = "Review and retry",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = true,
            )

            ErrorCode.DUPLICATE_OPERATION -> UserFacingState(
                type = UserFacingStateType.SUCCESS,
                title = "Already Done",
                message = "This action has already been completed.",
                errorCode = code,
                requiresUserAction = false,
            )

            ErrorCode.UNKNOWN_ERROR -> UserFacingState(
                type = UserFacingStateType.RECOVERABLE,
                title = "Error",
                message = technicalDetail?.takeIf { it.isNotBlank() && !it.contains("Exception") }
                    ?: "Couldn't complete that action.",
                suggestedAction = "Try again",
                actionIntent = "retry",
                errorCode = code,
                requiresUserAction = false,
            )
        }
    }

    fun fromException(e: Throwable): UserFacingState {
        val message = e.message ?: e.javaClass.simpleName
        val code = when {
            e is java.net.UnknownHostException -> ErrorCode.NETWORK_UNAVAILABLE
            e is java.net.SocketTimeoutException -> ErrorCode.NETWORK_TIMEOUT
            e is kotlinx.coroutines.CancellationException -> ErrorCode.USER_CANCELLED
            message.contains("Accessibility", ignoreCase = true) -> ErrorCode.ACCESSIBILITY_UNAVAILABLE
            message.contains("permission", ignoreCase = true) -> ErrorCode.PERMISSION_REQUIRED
            message.contains("timeout", ignoreCase = true) -> ErrorCode.TIMEOUT
            else -> ErrorCode.UNKNOWN_ERROR
        }
        return map(code, technicalDetail = message)
    }
}
