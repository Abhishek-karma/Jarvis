package com.jarvis.core.ml

import com.jarvis.core.common.DispatcherProvider
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Download/lifecycle state of the single installed on-device model (v1 supports one at a time). */
sealed interface LocalModelState {
    /** No catalog entries. */
    data object None : LocalModelState

    /** Catalog present but no model file on disk yet. */
    data object NotDownloaded : LocalModelState

    data class Downloading(
        val model: LocalModelSpec,
        val progress: Float,
    ) : LocalModelState

    /** Sideload copy from storage is running (indeterminate — SAF streams don't report size). */
    data class Importing(
        val model: LocalModelSpec,
    ) : LocalModelState

    data class Ready(
        val model: LocalModelSpec,
        val file: File,
    ) : LocalModelState

    data class Error(
        val message: String,
    ) : LocalModelState
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

    /**
     * Serializes the coordination sections of [startDownload] / [cancelDownload] /
     * [deleteModel] / [importModel] (status checks + job pointer swaps). Only fast,
     * non-blocking sections run under the lock — the multi-GB copy/download bodies stay
     * outside so callers on the main thread never stall behind them.
     */
    private val mutationLock = Any()

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
        // A killed process can leave a stale multi-GB .part behind (download or import).
        runCatching { File(modelsDir, spec.fileName + ".part").delete() }
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
        synchronized(mutationLock) {
            if (_status.value is LocalModelState.Downloading) return
            if (_status.value is LocalModelState.Ready) return
            downloadJob?.cancel()
            downloadJob =
                scope.launch(dispatchers.io) {
                    // Tie the blocking OkHttp call to coroutine cancellation: without this, cancelling
                    // the job leaves a multi-GB download writing to disk in the background.
                    val call =
                        okHttpClient.newCall(
                            Request
                                .Builder()
                                .url(spec.url)
                                .get()
                                .build(),
                        )
                    coroutineContext[Job]?.invokeOnCompletion { cause ->
                        if (cause is CancellationException) runCatching { call.cancel() }
                    }
                    _status.value = LocalModelState.Downloading(spec, 0f)
                    try {
                        val file = download(spec, call)
                        _status.value = LocalModelState.Ready(spec, file)
                    } catch (e: CancellationException) {
                        cleanupPart(spec)
                        _status.value = LocalModelState.NotDownloaded
                    } catch (e: Exception) {
                        cleanupPart(spec)
                        _status.value = LocalModelState.Error(errorMessage(e, spec))
                    }
                }
        }
    }

    fun cancelDownload() {
        synchronized(mutationLock) {
            downloadJob?.cancel()
            downloadJob = null
        }
        if (_status.value is LocalModelState.Downloading) {
            _status.value = LocalModelState.NotDownloaded
        }
    }

    fun deleteModel() {
        synchronized(mutationLock) {
            downloadJob?.cancel()
            downloadJob = null
        }
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
     * The copy runs synchronously on the caller's thread; it flips the status to [Importing] first
     * (so UIs can show progress for the multi-GB copy) and to [Ready] / [Error] on completion.
     * Pure-JVM: the caller supplies an [open] stream (Android opens it via ContentResolver), so
     * this path is unit-testable like the rest of the store.
     */
    fun importModel(
        fileName: String,
        displayName: String,
        open: () -> InputStream?,
    ): Result<Unit> {
        val targetSpec = availableModels.firstOrNull()
        val spec =
            targetSpec?.copy(displayName = displayName) ?: LocalModelSpec(
                id = "imported-$fileName",
                displayName = displayName,
                family = "Imported",
                fileName = fileName,
                url = "",
            )
        // Coordination (guard + job swap + Importing flip) is atomic with the other mutators;
        // the multi-GB copy itself runs outside the lock.
        synchronized(mutationLock) {
            when (_status.value) {
                is LocalModelState.Downloading ->
                    return Result.failure(IOException("A download is in progress. Cancel it first."))
                is LocalModelState.Importing ->
                    return Result.failure(IOException("An import is already in progress"))
                is LocalModelState.Ready ->
                    return Result.failure(IOException("A model is already installed. Remove it first."))
                else -> Unit
            }
            downloadJob?.cancel()
            downloadJob = null
            _status.value = LocalModelState.Importing(spec)
        }

        // Copy to a .part file, validate, then rename: a failed or interrupted import must never
        // leave a corrupt file under the final name (refresh() would otherwise mark it Ready).
        val target = modelFile(spec)
        val part = File(modelsDir, spec.fileName + ".part")
        part.parentFile?.mkdirs()
        return try {
            val input = open() ?: throw IOException("Could not open the selected model file")
            input.use { source ->
                part.outputStream().use { out -> source.copyTo(out) }
            }
            validateModelFile(part)
            finalizeDownload(part, target)
            synchronized(mutationLock) { _status.value = LocalModelState.Ready(spec, target) }
            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { part.delete() }
            val message = e.message ?: "Import failed"
            synchronized(mutationLock) { _status.value = LocalModelState.Error(message) }
            Result.failure(IOException(message))
        }
    }

    private fun modelFile(spec: LocalModelSpec): File =
        File(modelsDir, spec.fileName).apply {
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

    private suspend fun download(
        spec: LocalModelSpec,
        call: okhttp3.Call,
    ): File {
        require(spec.url.isNotBlank()) { "No download URL for ${spec.id}. Use the dev-asset path." }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val total = response.body?.contentLength() ?: -1L
            val part = File(modelsDir, spec.fileName + ".part")
            val target = modelFile(spec)
            part.parentFile?.mkdirs()
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
            finalizeDownload(part, target)
            return target
        }
    }

    /** Atomic-ish finalize: rename, falling back to copy+delete where rename fails. */
    private fun finalizeDownload(
        part: File,
        target: File,
    ) {
        if (target.exists() && !target.delete()) {
            throw IOException("Could not replace the previous model file")
        }
        if (part.renameTo(target)) return
        try {
            part.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() != part.length()) {
                target.delete()
                throw IOException("Could not finalize model file")
            }
            part.delete()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not finalize model file")
        }
    }

    private fun cleanupPart(spec: LocalModelSpec) {
        runCatching { File(modelsDir, spec.fileName + ".part").delete() }
    }

    /**
     * Rejects empty/tiny/unsupported files before they are marked Ready: such files abort
     * the native loader (process crash, not an exception). Deletes the bad file.
     */
    private fun validateModelFile(target: File) {
        val length = target.length()
        if (length < MIN_IMPORT_BYTES) {
            target.delete()
            throw IOException(
                "That file is only ${length / 1024} KB, which is not a valid on-device model " +
                    "(.litertlm bundles are ~2 GB). Pick the model file from the model page.",
            )
        }
        if (!OnDeviceModelFormat.isLiteRtLm(target)) {
            target.delete()
            throw IOException(
                "That file isn't a supported on-device model bundle (.litertlm). " +
                    "Download the model file from the model page and try again.",
            )
        }
    }

    private fun publishProgress(progress: Float) {
        val current = _status.value
        if (current is LocalModelState.Downloading) {
            _status.value = current.copy(progress = progress.coerceIn(0f, 1f))
        }
    }

    private fun verifyChecksum(
        file: File,
        spec: LocalModelSpec,
    ) {
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
            throw IOException("Checksum mismatch. The model file is corrupt or the wrong version.")
        }
    }

    private fun errorMessage(
        e: Exception,
        spec: LocalModelSpec,
    ): String {
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

        /** Smallest plausible import: model bundles are GB-scale; anything under this is wrong. */
        const val MIN_IMPORT_BYTES = 4L * 1024 * 1024
    }
}

object OnDeviceModelFormat {
    /**
     * True when [file] is a LiteRT LM `.litertlm` container (literal "LITERTLM" magic).
     * Runs on the LiteRT-LM engine ([LiteRtLmEngine]).
     */
    fun isLiteRtLm(file: File): Boolean = signature(file) == "LITERTLM"

    /** Recognized container signature at the head of [file], or null for anything else. */
    private fun signature(file: File): String? {
        val head = ByteArray(8)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (read < 8) return null
        if (String(head, 0, 8, Charsets.US_ASCII) == "LITERTLM") return "LITERTLM"
        return null
    }
}
