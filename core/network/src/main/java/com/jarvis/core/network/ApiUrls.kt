package com.jarvis.core.network

/**
 * Normalizes a stored provider base URL to the API root that adapters build on.
 * Providers append their versioned path themselves ("/v1/messages", "/v1/models"...),
 * while the UI default and pasted OpenAI/Anthropic doc URLs commonly *include* the
 * "/v1" suffix — without stripping it here, every request hit /v1/v1/... and 404'd.
 */
internal fun apiRoot(baseUrl: String): String = baseUrl.trimEnd('/').removeSuffix("/v1")
