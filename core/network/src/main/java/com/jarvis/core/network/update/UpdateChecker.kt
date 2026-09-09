package com.jarvis.core.network.update

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of one update check. */
sealed interface UpdateCheckResult {
    /** A newer release is available. */
    data class UpdateAvailable(
        val latestVersion: String,
        val apkUrl: String,
        val releaseNotesUrl: String,
    ) : UpdateCheckResult

    /** This build is the latest release. */
    data object UpToDate : UpdateCheckResult

    /** Check couldn't run (offline, rate-limited, GitHub error). Silent by design. */
    data object Unavailable : UpdateCheckResult
}


@Singleton
class UpdateChecker
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
    ) {
        private val http =
            okHttpClient
                .newBuilder()
                .callTimeout(Duration.ofSeconds(15))
                .build()

        private val moshi: Moshi = Moshi.Builder().build()


        suspend fun check(installedVersion: String): UpdateCheckResult =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request =
                        Request.Builder()
                            .url("https://api.github.com/repos/$REPO/releases/latest")
                            .header("Accept", "application/vnd.github+json")
                            .build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching UpdateCheckResult.Unavailable
                        val release =
                            moshi.adapter(ReleaseDto::class.java)
                                .fromJson(response.body?.string() ?: "")
                                ?: return@runCatching UpdateCheckResult.Unavailable
                        val tag = release.tagName ?: return@runCatching UpdateCheckResult.Unavailable
                        val latest = tag.removePrefix("v")
                        if (compareVersions(latest, installedVersion) <= 0) {
                            return@runCatching UpdateCheckResult.UpToDate
                        }
                        val apk = release.assets.orEmpty().firstOrNull { it.name?.endsWith(".apk") == true }
                        UpdateCheckResult.UpdateAvailable(
                            latestVersion = latest,
                            apkUrl = apk?.browserDownloadUrl ?: release.htmlUrl.orEmpty(),
                            releaseNotesUrl = release.htmlUrl.orEmpty(),
                        )
                    }
                }.getOrDefault(UpdateCheckResult.Unavailable)
            }

        companion object {
            const val REPO = "Abhishek-karma/Jarvis"


            internal fun compareVersions(a: String, b: String): Int {
                val pa = a.split(".").map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
                val pb = b.split(".").map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
                for (i in 0 until maxOf(pa.size, pb.size)) {
                    val x = pa.getOrElse(i) { 0L }
                    val y = pb.getOrElse(i) { 0L }
                    if (x != y) return x.compareTo(y)
                }
                return 0
            }
        }
    }

@JsonClass(generateAdapter = true)
internal data class ReleaseDto(
    @Json(name = "tag_name") val tagName: String?,
    @Json(name = "html_url") val htmlUrl: String?,
    val assets: List<AssetDto>?,
)

@JsonClass(generateAdapter = true)
internal data class AssetDto(
    val name: String?,
    @Json(name = "browser_download_url") val browserDownloadUrl: String?,
)
