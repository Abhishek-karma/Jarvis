package com.jarvis.core.network

/**
 * Standardized taxonomy of LLM and network provider error categories.
 * Maps low-level HTTP and transport error codes to user-facing error kinds
 * with clear recovery actions.
 */
enum class ProviderErrorKind {
    AUTHENTICATION,
    RATE_LIMIT,
    NETWORK,
    TIMEOUT,
    UNSUPPORTED_CAPABILITY,
    CONTEXT_TOO_LARGE,
    PROVIDER_UNAVAILABLE,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

/**
 * User-friendly representation of a provider error with actionable recovery suggestions.
 */
data class CategorizedProviderError(
    val kind: ProviderErrorKind,
    val title: String,
    val description: String,
    val recoveryAction: String,
    val isRetryable: Boolean,
    val rawCode: String? = null,
    val rawMessage: String? = null,
) {
    companion object {
        fun classify(code: String?, message: String?): CategorizedProviderError {
            val codeStr = code?.lowercase() ?: ""
            val msgStr = message?.lowercase() ?: ""

            return when {
                codeStr in listOf("401", "403", "unauthorized", "forbidden") ||
                    msgStr.contains("api key") ||
                    msgStr.contains("unauthorized") ||
                    msgStr.contains("authentication") ||
                    msgStr.contains("permission denied") -> CategorizedProviderError(
                    kind = ProviderErrorKind.AUTHENTICATION,
                    title = "Authentication Error",
                    description = "Invalid or expired API key. Please check your provider credentials.",
                    recoveryAction = "Check Provider Settings",
                    isRetryable = false,
                    rawCode = code,
                    rawMessage = message,
                )

                codeStr in listOf("429", "rate_limit", "quota") ||
                    msgStr.contains("rate limit") ||
                    msgStr.contains("quota exceeded") ||
                    msgStr.contains("too many requests") -> CategorizedProviderError(
                    kind = ProviderErrorKind.RATE_LIMIT,
                    title = "Rate Limit Reached",
                    description = "The provider's rate limit or quota has been reached. Please wait a moment before trying again.",
                    recoveryAction = "Retry in a Moment",
                    isRetryable = true,
                    rawCode = code,
                    rawMessage = message,
                )

                codeStr in listOf("timeout", "408", "504") ||
                    msgStr.contains("timeout") ||
                    msgStr.contains("timed out") -> CategorizedProviderError(
                    kind = ProviderErrorKind.TIMEOUT,
                    title = "Request Timed Out",
                    description = "The server took too long to respond. The model may be under heavy load.",
                    recoveryAction = "Retry Request",
                    isRetryable = true,
                    rawCode = code,
                    rawMessage = message,
                )

                codeStr in listOf("network", "connect", "dns", "unreachable") ||
                    msgStr.contains("unable to resolve host") ||
                    msgStr.contains("connection refused") ||
                    msgStr.contains("failed to connect") ||
                    msgStr.contains("no network") -> CategorizedProviderError(
                    kind = ProviderErrorKind.NETWORK,
                    title = "Network Connection Error",
                    description = "Cannot reach the server. Please check your internet connection.",
                    recoveryAction = "Check Network",
                    isRetryable = true,
                    rawCode = code,
                    rawMessage = message,
                )

                codeStr in listOf("500", "502", "503", "unavailable", "server_error") ||
                    msgStr.contains("service unavailable") ||
                    msgStr.contains("bad gateway") ||
                    msgStr.contains("internal server error") -> CategorizedProviderError(
                    kind = ProviderErrorKind.PROVIDER_UNAVAILABLE,
                    title = "Service Unavailable",
                    description = "The AI provider service is temporarily experiencing difficulties.",
                    recoveryAction = "Retry Later",
                    isRetryable = true,
                    rawCode = code,
                    rawMessage = message,
                )

                codeStr in listOf("context_length_exceeded", "max_tokens") ||
                    msgStr.contains("context length") ||
                    msgStr.contains("maximum context") ||
                    msgStr.contains("too many tokens") -> CategorizedProviderError(
                    kind = ProviderErrorKind.CONTEXT_TOO_LARGE,
                    title = "Context Too Long",
                    description = "The conversation history exceeds the model's token limit.",
                    recoveryAction = "Start New Chat",
                    isRetryable = false,
                    rawCode = code,
                    rawMessage = message,
                )

                codeStr in listOf("unsupported", "not_supported") ||
                    msgStr.contains("not supported") ||
                    msgStr.contains("unsupported capability") -> CategorizedProviderError(
                    kind = ProviderErrorKind.UNSUPPORTED_CAPABILITY,
                    title = "Unsupported Capability",
                    description = "The selected model does not support this operation (such as tools or reasoning).",
                    recoveryAction = "Switch Model",
                    isRetryable = false,
                    rawCode = code,
                    rawMessage = message,
                )

                codeStr in listOf("malformed", "json", "parse_error") ||
                    msgStr.contains("malformed") ||
                    msgStr.contains("unexpected response") -> CategorizedProviderError(
                    kind = ProviderErrorKind.MALFORMED_RESPONSE,
                    title = "Malformed Response",
                    description = "The model returned an unparseable response.",
                    recoveryAction = "Regenerate",
                    isRetryable = true,
                    rawCode = code,
                    rawMessage = message,
                )

                else -> CategorizedProviderError(
                    kind = ProviderErrorKind.UNKNOWN,
                    title = "Provider Error",
                    description = message ?: "An unexpected error occurred during generation.",
                    recoveryAction = "Retry",
                    isRetryable = true,
                    rawCode = code,
                    rawMessage = message,
                )
            }
        }
    }
}
