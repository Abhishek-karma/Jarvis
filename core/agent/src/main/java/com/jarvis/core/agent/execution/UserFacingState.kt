package com.jarvis.core.agent.execution

enum class UserFacingStateType {
    SUCCESS,
    PARTIAL,
    RECOVERABLE,
    ACTION_REQUIRED,
    CANCELLED,
    FAILED,
    WAITING,
}

data class UserFacingState(
    val type: UserFacingStateType,
    val title: String,
    val message: String,
    val suggestedAction: String? = null,
    val actionIntent: String? = null,
    val errorCode: ErrorCode? = null,
    val requiresUserAction: Boolean = false,
)
