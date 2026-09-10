package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier

object WebTools {
    const val FETCH_URL = "fetch_url"
    const val SEARCH_WEB = "search_web"
    const val WEB_SEARCH = "web_search"

    val manifestNames: List<String> = listOf(FETCH_URL, SEARCH_WEB, WEB_SEARCH)

    /** Fetched page content, already reduced to readable plain text. */
    data class FetchedPage(
        val title: String?,
        val text: String,
    )

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

    fun all(
        fetch: suspend (url: String) -> Result<FetchedPage>,
        search: (suspend (query: String, maxResults: Int) -> Result<List<SearchResult>>)? = null,
    ): List<Tool> = buildList {
        add(fetchUrl(fetch))
        if (search != null) {
            add(searchWeb(search))
            add(searchWeb(search, toolName = WEB_SEARCH))
        }
    }

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
                val url = args.string("url") ?: args.string("link") ?: args.string("target_url")
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
                                    "untrusted" to true,
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

    fun searchWeb(
        search: suspend (query: String, maxResults: Int) -> Result<List<SearchResult>>,
        toolName: String = SEARCH_WEB,
    ): Tool =
        object : Tool {
            override val name = toolName
            override val description =
                "Search the public web for real-time information, answers, links, documentation, or current events. Read-only."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = SEARCH_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val query = args.string("query")
                    ?: args.string("q")
                    ?: args.string("search_query")
                    ?: args.string("keywords")
                    ?: args.string("input")
                    ?: args.string("text")
                if (query.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: query is required.",
                        error = "query is required",
                    )
                }
                val maxResults = args.int("max_results") ?: 5
                return search(query.trim(), maxResults.coerceIn(1, 10)).fold(
                    onSuccess = { results ->
                        if (results.isEmpty()) {
                            ToolResult(
                                success = true,
                                observationText = "No results found for '$query'.",
                                structuredData = mapOf("count" to 0),
                            )
                        } else {
                            val text = results.joinToString("\n\n") { r ->
                                "[${r.title}]\nURL: ${r.url}\n${r.snippet}"
                            }
                            ToolResult(
                                success = true,
                                observationText = text,
                                structuredData = mapOf(
                                    "count" to results.size,
                                    "untrusted" to true,
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Web search failed for '$query'.",
                            error = error.message ?: "Search failed",
                        )
                    },
                )
            }
        }

    /** Cap the text fed back into the ReAct loop — a full article would swamp the context. */
    internal const val MAX_CHARS = 6_000

    private const val FETCH_SCHEMA =
        """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""

    private const val SEARCH_SCHEMA =
        """{"type":"object","properties":{"query":{"type":"string","description":"Search query keywords"},"max_results":{"type":"integer","description":"Maximum number of results to return (1-10)"}},"required":["query"]}"""
}
