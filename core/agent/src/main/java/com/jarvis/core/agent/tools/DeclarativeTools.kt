package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.ToolSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class HttpToolConfig(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val bodyTemplate: String? = null,
)

/**
 * A safe, declarative HTTP-based tool that executes remote API calls
 * while strictly enforcing SSRF protection and parameter sanitization.
 */
class DeclarativeHttpTool(
    override val name: String,
    override val description: String,
    override val parametersSchemaJson: String,
    override val tier: PermissionTier = PermissionTier.READ_ONLY,
    override val source: ToolSource = ToolSource.CUSTOM,
    override val version: String = "1.0.0",
    override val enabled: Boolean = true,
    private val config: HttpToolConfig,
    private val client: OkHttpClient = defaultHttpClient,
) : Tool {

    override suspend fun execute(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val json = Json { ignoreUnknownKeys = true }
            val parsedArgs: JsonObject = runCatching {
                json.parseToJsonElement(argsJson).jsonObject
            }.getOrDefault(buildJsonObject { })

            var resolvedUrl = config.url
            for ((key, value) in parsedArgs) {
                val primValue = (value as? JsonPrimitive)?.content ?: value.toString()
                // URL-encode path substitutions to prevent path traversal / query-injection
                // via untrusted argument values (e.g. "path=/etc/passwd", "q=x?y=1#z").
                val encoded = URLEncoder.encode(primValue, "UTF-8")
                resolvedUrl = resolvedUrl.replace("{$key}", encoded)
            }

            ensurePublicHttpUrl(resolvedUrl)

            val httpUrlBuilder = resolvedUrl.toHttpUrlOrNull()?.newBuilder()
                ?: error("Invalid URL format: $resolvedUrl")

            for ((qKey, qVal) in config.queryParams) {
                var resolvedQVal = qVal
                for ((argKey, argVal) in parsedArgs) {
                    val primVal = (argVal as? JsonPrimitive)?.content ?: argVal.toString()
                    resolvedQVal = resolvedQVal.replace("{$argKey}", primVal)
                }
                httpUrlBuilder.addQueryParameter(qKey, resolvedQVal)
            }

            val finalUrl = httpUrlBuilder.build()

            val reqBuilder = Request.Builder().url(finalUrl)
            for ((hKey, hVal) in config.headers) {
                var resolvedHVal = hVal
                for ((argKey, argVal) in parsedArgs) {
                    val primVal = (argVal as? JsonPrimitive)?.content ?: argVal.toString()
                    resolvedHVal = resolvedHVal.replace("{$argKey}", primVal)
                }
                reqBuilder.addHeader(hKey, resolvedHVal)
            }

            if (config.method.equals("POST", ignoreCase = true) || config.method.equals("PUT", ignoreCase = true)) {
                var bodyStr = config.bodyTemplate ?: argsJson
                for ((argKey, argVal) in parsedArgs) {
                    val primVal = (argVal as? JsonPrimitive)?.content ?: argVal.toString()
                    bodyStr = bodyStr.replace("{$argKey}", primVal)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                reqBuilder.method(config.method.uppercase(), bodyStr.toRequestBody(mediaType))
            } else {
                reqBuilder.get()
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val truncatedBody = if (body.length > 2000) body.take(2000) + "\n...[truncated]" else body
                    ToolResult(
                        success = true,
                        observationText = "HTTP ${response.code}: $truncatedBody",
                        structuredData = mapOf("status" to response.code, "body" to truncatedBody),
                    )
                } else {
                    ToolResult(
                        success = false,
                        observationText = "HTTP ${response.code} error: $body",
                        error = "HTTP status ${response.code}",
                    )
                }
            }
        }.getOrElse { err ->
            ToolResult(
                success = false,
                observationText = "HTTP Tool execution failed: ${err.message}",
                error = err.message,
            )
        }
    }

    companion object {
        private val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun ensurePublicHttpUrl(url: String) {
            val parsed = url.toHttpUrlOrNull() ?: error("Not a valid URL")
            if (parsed.scheme != "http" && parsed.scheme != "https") error("Only http(s) URLs are allowed")
            val host = parsed.host
            if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1") {
                error("Localhost is not allowed")
            }
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) error("Host did not resolve")
            for (address in addresses) {
                val blocked = address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    address.isAnyLocalAddress ||
                    address.isMulticastAddress ||
                    (address is java.net.Inet4Address &&
                        (address.address[0].toInt() and 0xFF) == 169 &&
                        (address.address[1].toInt() and 0xFF) == 254)
                if (blocked) error("Refusing to fetch private/local network address")
            }
        }
    }
}
