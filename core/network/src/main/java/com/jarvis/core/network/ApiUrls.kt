package com.jarvis.core.network


internal fun apiRoot(baseUrl: String): String = baseUrl.trimEnd('/').removeSuffix("/v1")
