package com.jarvis.core.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Web capabilities - search, browse, fetch
 */
class WebCapability(
    private val okHttpClient: OkHttpClient,
) : Capability {

    override val id = CapabilityIds.WEB_SEARCH

    override val description = "Search the web, fetch URLs, and retrieve information"

    override val requiredPermissions = listOf(android.Manifest.permission.INTERNET)

    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: search, fetch, browse",
        )

        return when (action) {
            "search" -> searchWeb(request)
            "fetch" -> fetchUrl(request)
            "browse" -> browseWeb(request)
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown web action: $action",
            )
        }
    }

    private suspend fun searchWeb(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val query = request.parameters["query"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'query' parameter"
        )
        val maxResults = (request.parameters["max_results"] as? Int) ?: 5

        try {
            val results = mutableListOf<Map<String, Any>>()
            val formBody = FormBody.Builder()
                .add("q", query)
                .build()
            val ddgLiteRequest = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                .header("Referer", "https://lite.duckduckgo.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = okHttpClient.newCall(ddgLiteRequest).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val linkRegex = Regex(
                        """<a\b[^>]*href=['"]([^'"]+)['"][^>]*class=['"]result-link['"][^>]*>([\s\S]*?)</a>|<a\b[^>]*class=['"]result-link['"][^>]*href=['"]([^'"]+)['"][^>]*>([\s\S]*?)</a>""",
                        RegexOption.IGNORE_CASE,
                    )
                    val snippetRegex = Regex(
                        """<td\b[^>]*class=['"]result-snippet['"][^>]*>([\s\S]*?)</td>""",
                        RegexOption.IGNORE_CASE,
                    )

                    val linkMatches = linkRegex.findAll(html).toList()
                    val snippetMatches = snippetRegex.findAll(html).toList()

                    for (i in linkMatches.indices) {
                        if (results.size >= maxResults) break
                        val m = linkMatches[i]
                        val rawUrl = m.groupValues[1].ifEmpty { m.groupValues[3] }
                        val rawTitle = m.groupValues[2].ifEmpty { m.groupValues[4] }
                        val title = decodeHtmlEntities(rawTitle.replace(Regex("<[^>]+>"), "").trim())
                        val snippet = if (i < snippetMatches.size) {
                            decodeHtmlEntities(snippetMatches[i].groupValues[1].replace(Regex("<[^>]+>"), "").trim())
                        } else ""

                        val resolvedUrl = when {
                            rawUrl.contains("uddg=") -> {
                                val match = Regex("uddg=([^&]+)").find(rawUrl)
                                if (match != null) URLDecoder.decode(match.groupValues[1], "UTF-8") else rawUrl
                            }
                            rawUrl.startsWith("//") -> "https:$rawUrl"
                            else -> rawUrl
                        }

                        if (title.isNotBlank() && resolvedUrl.isNotBlank() && !resolvedUrl.contains("duckduckgo.com")) {
                            results.add(
                                mapOf<String, Any>(
                                    "title" to title,
                                    "url" to resolvedUrl,
                                    "snippet" to snippet,
                                )
                            )
                        }
                    }
                }
            }

            CapabilityResult.Success(
                output = if (results.isNotEmpty()) {
                    "Search results for '$query': ${results.joinToString("; ") { "${it["title"]} - ${it["url"]}" }}"
                } else {
                    "No results found for '$query'"
                },
                structuredData = mapOf("results" to results),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("SEARCH_ERROR", "Failed to search web: ${e.message}")
        }
    }

    private suspend fun fetchUrl(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val url = request.parameters["url"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'url' parameter"
        )

        try {
            ensurePublicHttpUrl(url)
            val userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain,*/*;q=0.8")
                .get()
                .build()

            val response = okHttpClient.newCall(req).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@use CapabilityResult.Failure("HTTP_ERROR", "HTTP ${resp.code}")
                val contentType = resp.header("Content-Type") ?: ""
                if (!contentType.contains("text/", ignoreCase = true) &&
                    !contentType.contains("json", ignoreCase = true) &&
                    !contentType.contains("markdown", ignoreCase = true)
                ) {
                    return@use CapabilityResult.Failure("CONTENT_TYPE", "Not a text page (Content-Type: ${contentType.ifBlank { "unknown" }})")
                }
                val raw = resp.body?.string() ?: return@use CapabilityResult.Failure("EMPTY_BODY", "Empty body")
                val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                    .find(raw)
                    ?.groupValues
                    ?.get(1)
                    ?.let { decodeHtmlEntities(it).trim() }
                CapabilityResult.Success(
                    output = "Fetched: ${title ?: url}\n\n${htmlToText(raw).take(3000)}",
                    structuredData = mapOf<String, Any>(
                        "title" to (title ?: ""),
                        "url" to url,
                        "length" to raw.length,
                    ),
                )
            }
        } catch (e: Exception) {
            CapabilityResult.Failure("FETCH_ERROR", "Failed to fetch URL: ${e.message}")
        }
    }

    private suspend fun browseWeb(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        fetchUrl(request)
    }

    private fun htmlToText(html: String): String =
        html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("<[^>]+>"), " ")
            .let(::decodeHtmlEntities)
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun decodeHtmlEntities(text: String): String =
        text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

    private fun ensurePublicHttpUrl(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not a valid URL")
        if (parsed.scheme != "http" && parsed.scheme != "https") throw IllegalArgumentException("Only http(s) URLs are allowed")
        val host = parsed.host
        if (host == "localhost") throw IllegalArgumentException("Localhost is not allowed")
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty()) throw IllegalArgumentException("Host did not resolve")
        for (address in addresses) {
            val blocked =
                address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    address.isAnyLocalAddress ||
                    address.isMulticastAddress ||
                    (address is Inet4Address &&
                        (address.address[0].toInt() and 0xFF) == 169 &&
                        (address.address[1].toInt() and 0xFF) == 254)
            if (blocked) throw IllegalArgumentException("Refusing to fetch a private or local address")
        }
    }
}