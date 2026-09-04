package com.jarvis.core.ml

import com.jarvis.core.common.DispatcherProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** Download/lifecycle state of the single installed on-device model (v1 supports one at a time). */
sealed interface LocalModelState {
    /** No catalog entries. */
    data object None : LocalModelState

    /** Catalog present but no model file on disk yet. */
    data object NotDownloaded : LocalModelState

    data class Downloading(val model: LocalModelSpec, val progress: Float) : LocalModelState

    data class Ready(val model: LocalModelSpec, val file: File) : LocalModelState

    data class Error(val message: String) : LocalModelState
}

/**
 * Owns the on-device model file lifecycle: detection of an existing file, copy from debug
 * dev-model assets, and streamed download with progress + optional checksum.
 *
 * Pure-JVM apart from the injected OkHttp client and asset opener, so every path is unit-tested
 * (MockWebServer for downloads, @TempDir for files) — only the Android [LocalModelStore] binding
 * in LocalMlModule touches Context.
 */
class LocalModelStore(
    private val catalog: LocalModelCatalog,
    private val modelsDir: File,
    private val openAsset: (fileName: String) -> InputStream?,
    private val okHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val _status = MutableStateFlow<LocalModelState>(LocalModelState.None)
    val status: StateFlow<LocalModelState> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var downloadJob: Job? = null

    val availableModels: List<LocalModelSpec> get() = catalog.load()

    init {
        scope.launch { refresh() }
    }

    /** Re-derive state from disk: existing file → Ready, dev asset → copy → Ready, else NotDownloaded. */
    suspend fun refresh() {
        val models = availableModels
        if (models.isEmpty()) {
            _status.value = LocalModelState.None
            return
        }
        val spec = models.first()
        val installed = modelFile(spec)
        if (installed.isFile && installed.length() > 0) {
            _status.value = LocalModelState.Ready(spec, installed)
            return
        }
        val copied = copyFromDevAssets(spec)
        if (copied != null) {
            _status.value = LocalModelState.Ready(spec, copied)
            return
        }
        _status.value = LocalModelState.NotDownloaded
    }

    /** Starts a background download for [modelId]; a no-op if already downloaded or downloading. */
    fun startDownload(modelId: String) {
        val spec = availableModels.firstOrNull { it.id == modelId } ?: return
        if (_status.value is LocalModelState.Downloading) return
        if (_status.value is LocalModelState.Ready) return
        downloadJob?.cancel()
        downloadJob = scope.launch(dispatchers.io) {
            _status.value = LocalModelState.Downloading(spec, 0f)
            try {
                val file = download(spec)
                _status.value = LocalModelState.Ready(spec, file)
            } catch (e: CancellationException) {
                _status.value = LocalModelState.NotDownloaded
            } catch (e: Exception) {
                _status.value = LocalModelState.Error(errorMessage(e, spec))
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (_status.value is LocalModelState.Downloading) {
            _status.value = LocalModelState.NotDownloaded
        }
    }

    fun deleteModel() {
        downloadJob?.cancel()
        downloadJob = null
        val models = availableModels
        if (models.isNotEmpty()) modelFile(models.first()).delete()
        _status.value = LocalModelState.NotDownloaded
    }

    /**
     * Sideloads a model file from local storage (the SAF picker's stream): copies it into the
     * on-device models dir — replacing any installed/downloaded model — and marks the store Ready
     * so routing can use it immediately. [fileName] is the stable on-disk name, [displayName] the
     * friendly label shown in the card.
     *
     * Pure-JVM: the caller supplies an [open] stream (Android opens it via ContentResolver), so
     * this path is unit-testable like the rest of the store.
     */
    fun importModel(fileName: String, displayName: String, open: () -> InputStream?): Result<Unit> {
        downloadJob?.cancel()
        downloadJob = null
        return runCatching {
            val input = open() ?: throw IOException("Could not open the selected model file")
            val models = availableModels
            val targetSpec = models.firstOrNull()
            val target = targetSpec?.let { modelFile(it) } ?: File(modelsDir, fileName).apply {
                parentFile?.mkdirs()
            }
            input.use { source ->
                target.outputStream().use { out -> source.copyTo(out) }
            }
            if (target.length() <= 0) {
                target.delete()
                throw IOException("The selected file is empty — not a valid model.")
            }
            // Reuse the catalog spec (so refresh()/runtime stay consistent with the on-disk name)
            // but surface the user's filename as the label.
            val spec = targetSpec?.copy(displayName = displayName) ?: LocalModelSpec(
                id = "imported-$fileName",
                displayName = displayName,
                family = "Imported",
                fileName = fileName,
                url = "",
            )
            _status.value = LocalModelState.Ready(spec, target)
        }
    }

    private fun modelFile(spec: LocalModelSpec): File = File(modelsDir, spec.fileName).apply {
        parentFile?.mkdirs()
    }

    /** Debug-build dev models (scripts/fetch-dev-models.sh) skip the network. */
    private fun copyFromDevAssets(spec: LocalModelSpec): File? {
        val target = modelFile(spec)
        val input = runCatching { openAsset(spec.fileName) }.getOrNull() ?: return null
        return try {
            input.use { source ->
                target.outputStream().use { out -> source.copyTo(out) }
            }
            if (target.length() > 0) target else null
        } catch (e: IOException) {
            target.delete()
            null
        }
    }

    private suspend fun download(spec: LocalModelSpec): File {
        require(spec.url.isNotBlank()) { "No download URL for ${spec.id} — use the dev-asset path." }
        val request = Request.Builder().url(spec.url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val total = response.body?.contentLength() ?: -1L
            val part = File(modelsDir, spec.fileName + ".part")
            val target = modelFile(spec)
            response.body?.byteStream()?.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var written = 0L
                    var lastPublish = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0 && written - lastPublish >= PROGRESS_PUBLISH_BYTES) {
                            lastPublish = written
                            publishProgress(written.toFloat() / total.toFloat())
                        }
                    }
                }
            }
            publishProgress(1f)
            verifyChecksum(part, spec)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Could not finalize model file")
            }
            return target
        }
    }

    private fun publishProgress(progress: Float) {
        val current = _status.value
        if (current is LocalModelState.Downloading) {
            _status.value = current.copy(progress = progress.coerceIn(0f, 1f))
        }
    }

    private fun verifyChecksum(file: File, spec: LocalModelSpec) {
        if (spec.checksumSha256.isBlank()) return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(spec.checksumSha256, ignoreCase = true)) {
            file.delete()
            throw IOException("Checksum mismatch — model file is corrupt or wrong version.")
        }
    }

    private fun errorMessage(e: Exception, spec: LocalModelSpec): String {
        val httpCode = (e.message ?: "").substringAfter("HTTP ", "")
        if (httpCode in setOf("401", "403", "404", "407")) {
            return "Download failed (HTTP $httpCode). If the model page requires a license " +
                "acceptance, download \"${spec.fileName}\" from ${spec.manualPage.ifBlank { "the model page" }} " +
                "and place it in app/src/debug/assets/models-dev/ for debug builds."
        }
        return "Download failed: ${e.message ?: "unknown error"}"
    }

    private companion object {
        /** Progress republished at most every 64 KB so a 1.6 GB download doesn't spam state. */
        const val PROGRESS_PUBLISH_BYTES = 64L * 1024
    }
}
