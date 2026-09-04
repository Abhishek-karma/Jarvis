package com.jarvis.core.ml

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.InputStream

/** Inference runtime a model file targets. MEDIAPIPE ships now; LITERT_LM is a later drop-in engine. */
enum class LocalRuntime { MEDIAPIPE, LITERT_LM }

/** One entry from the bundled model catalog (assets/local-models.json). */
@JsonClass(generateAdapter = true)
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val family: String = "",
    val runtime: LocalRuntime = LocalRuntime.MEDIAPIPE,
    val fileName: String,
    val url: String = "",
    /** Human page for the file — surfaced when an automated download is gated/blocked. */
    val manualPage: String = "",
    /** Empty = download is atomic but not checksum-verified. */
    val checksumSha256: String = "",
    val approxSizeLabel: String = "",
    val ramNote: String = "",
    val license: String = "",
)

@JsonClass(generateAdapter = true)
internal data class LocalModelsFile(
    val version: Int = 1,
    val models: List<LocalModelSpec> = emptyList(),
)

/**
 * Loads the bundled on-device model catalog. `source` is injected so the Android build reads
 * assets while JVM tests read a classpath resource. Parsing is Moshi codegen (same setup as
 * :core:network DTOs), so it is fully unit-testable.
 */
class LocalModelCatalog(
    private val source: () -> InputStream?,
    private val moshi: Moshi = Moshi.Builder().build(),
) {
    fun load(): List<LocalModelSpec> {
        val input = source() ?: return emptyList()
        val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(text)
    }

    internal fun parse(text: String): List<LocalModelSpec> =
        runCatching { moshi.adapter(LocalModelsFile::class.java).fromJson(text) }
            .getOrNull()
            ?.models
            .orEmpty()
}
