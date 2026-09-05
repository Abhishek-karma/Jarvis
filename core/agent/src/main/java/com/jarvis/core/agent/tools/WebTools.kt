package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult

/**
 * Web tools (v0.5 catalog, `06-AGENT §3`): fetch a URL and return readable plain text.
 * Read-only. The HTTP fetcher is injected as a lambda (URL in, body text out — the
 * binding does the HTML-to-text extraction) so the tool is JVM-unit-testable.
 */
object WebTools {
    const val FETCH_URL = "fetch_url"

    val manifestNames: List<String> = listOf(FETCH_URL)

    /** Fetched page content, already reduced to readable plain text. */
    data class FetchedPage(
        val title: String?,
        val text: String,
    )

    fun all(
        fetch: suspend (url: String) -> Result<FetchedPage>,
    ): List<Tool> = listOf(fetchUrl(fetch))

    fun fetchUrl(fetch: suspend (String) -> Result<FetchedPage>): Tool =
        object : Tool {
            override val name = FETCH_URL
            override val description =
                "Fetch a web page by URL and return its readable plain text. Read-only; " +
                    "use for looking up public information the user links or names."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = FETCH_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val url = args.string("url")
                if (url.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: url is required.",
                        error = "url is required",
                    )
                }
                return fetch(url.trim()).fold(
                    onSuccess = { page ->
                        if (page.text.isBlank()) {
                            ToolResult(
                                success = true,
                                observationText =
                                    "Fetched ${url} but it had no readable text " +
                                        "(likely a binary or script-only page).",
                                structuredData = mapOf("chars" to 0),
                            )
                        } else {
                            ToolResult(
                                success = true,
                                observationText = page.text.take(MAX_CHARS),
                                structuredData = mapOf(
                                    "chars" to page.text.length,
                                    "truncated" to (page.text.length > MAX_CHARS),
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not fetch ${url}.",
                            error = error.message ?: "URL fetch failed",
                        )
                    },
                )
            }
        }

    /** Cap the text fed back into the ReAct loop — a full article would swamp the context. */
    internal const val MAX_CHARS = 6_000

    private const val FETCH_SCHEMA =
        """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
}
