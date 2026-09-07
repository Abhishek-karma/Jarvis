package com.jarvis.core.ml

import com.jarvis.core.common.DispatcherProvider
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
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


sealed interface LocalModelState {
    /** No catalog entries and nothing installed. */
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

    /** The ACTIVE installed model. Other installed models are listed in [LocalModelStore.installedModels]. */
    data class Ready(
        val model: LocalModelSpec,
        val file: File,
    ) : LocalModelState

    data class Error(
        val message: String,
    ) : LocalModelState
}

/** One model sitting in the models dir, ready to be activated. */
data class InstalledModel(
    val spec: LocalModelSpec,
    val file: File,
    /** True when this model is the one the runtime loads. */
    val isActive: Boolean,
    /** File size in bytes — shown verbatim for imports (catalog models carry a label). */
    val sizeBytes: Long,
)

@JsonClass(generateAdapter = true)
internal data class ImportedModelEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long = 0,
)

@JsonClass(generateAdapter = true)
internal data class ModelRegistry(
    /** Which installed model the runtime should load; blank = first installed wins. */
    val activeId: String = "",
    val imports: List<ImportedModelEntry> = emptyList(),
)


class LocalModelStore(
    private val catalog: LocalModelCatalog,
    private val modelsDir: File,
    private val openAsset: (fileName: String) -> InputStream?,
    private val okHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val _status = MutableStateFlow<LocalModelState>(LocalModelState.None)
    val status: StateFlow<LocalModelState> = _status.asStateFlow()

    /** Every model sitting in the models dir, with the active one flagged. */
    private val _installedModels = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installedModels: StateFlow<List<InstalledModel>> = _installedModels.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var downloadJob: Job? = null


    private val mutationLock = Any()

    private val moshi = Moshi.Builder().build()
    private val registryAdapter = moshi.adapter(ModelRegistry::class.java)

    val availableModels: List<LocalModelSpec> get() = catalog.load()

    init {
        scope.launch { refresh() }
    }

    private fun registryFile(): File = File(modelsDir, REGISTRY_FILE)

    private fun readRegistry(): ModelRegistry =
        runCatching {
            val file = registryFile()
            if (!file.isFile) return ModelRegistry()
            registryAdapter.fromJson(file.readText()) ?: ModelRegistry()
        }.getOrDefault(ModelRegistry())

    private fun writeRegistry(registry: ModelRegistry) {
        modelsDir.mkdirs()
        val file = registryFile()
        if (registry.imports.isEmpty() && registry.activeId.isBlank()) {
            file.delete()
            return
        }
        runCatching { file.writeText(registryAdapter.toJson(registry)) }
    }

    /** Re-derive state from disk: pick the active model, list everything installed. */
    suspend fun refresh() {






        synchronized(mutationLock) {
            if (
                _status.value is LocalModelState.Importing ||
                _status.value is LocalModelState.Downloading
            ) return
            runCatching { modelsDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach(File::delete) }
        }
        val registry = readRegistry()
        val catalogById = availableModels.associateBy { it.id }



        if (catalogById.isNotEmpty()) {
            catalogById.values.forEach { spec ->
                val file = modelFile(spec)
                if (!(file.isFile && file.length() > 0)) copyFromDevAssets(spec)
            }
        }


        val installed = buildList {
            catalogById.values.forEach { spec ->
                val file = modelFile(spec)
                if (file.isFile && file.length() > 0) add(InstalledModel(spec, file, false, file.length()))
            }
            registry.imports.forEach { entry ->
                val file = File(modelsDir, entry.fileName)
                if (file.isFile && file.length() > 0) {
                    val spec =
                        LocalModelSpec(
                            id = entry.id,
                            displayName = entry.displayName,
                            family = "Imported",
                            fileName = entry.fileName,
                        )
                    add(InstalledModel(spec, file, false, entry.sizeBytes.takeIf { it > 0 } ?: file.length()))
                }
            }
        }

        val active =
            installed.firstOrNull { it.spec.id == registry.activeId }
                ?: installed.firstOrNull()
        _installedModels.value = installed.map { it.copy(isActive = it.spec.id == active?.spec?.id) }

        if (installed.isEmpty()) {
            _status.value =
                if (catalogById.isEmpty() && registry.imports.isEmpty()) {
                    LocalModelState.None
                } else {
                    LocalModelState.NotDownloaded
                }
            return
        }
        val activeModel = active!!
        _status.value = LocalModelState.Ready(activeModel.spec, activeModel.file)
    }

    /** Starts a background download for [modelId]; a no-op if already downloading. */
    fun startDownload(modelId: String) {
        val spec = availableModels.firstOrNull { it.id == modelId } ?: return
        synchronized(mutationLock) {
            if (_status.value is LocalModelState.Downloading) return
            if (_installedModels.value.any { it.spec.id == modelId }) return
            downloadJob?.cancel()
            downloadJob =
                scope.launch(dispatchers.io) {


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
                        publishInstalled(spec.id) { ModelRegistry(activeId = spec.id) }
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


    @Volatile
    private var importCancelled = false

    fun cancelImport() {
        importCancelled = true
        if (_status.value is LocalModelState.Importing) {
            _status.value = LocalModelState.Error("Import cancelled")
        }
    }

    /** Deletes [modelId]; if it was active, another installed model takes over (or none). */
    fun deleteModel(modelId: String) {
        synchronized(mutationLock) {
            downloadJob?.cancel()
            downloadJob = null
        }
        val registry = readRegistry()
        val catalogSpec = availableModels.firstOrNull { it.id == modelId }
        if (catalogSpec != null) {
            modelFile(catalogSpec).delete()
        } else {
            registry.imports.firstOrNull { it.id == modelId }?.let { entry ->
                File(modelsDir, entry.fileName).delete()
            }
        }
        val remaining = registry.imports.filter { it.id != modelId }
        val newActive = registry.activeId.takeUnless { it == modelId }.orEmpty()
        writeRegistry(registry.copy(activeId = newActive, imports = remaining))



        val survivors = _installedModels.value.filter { it.spec.id != modelId }
        val nextActive = survivors.firstOrNull { it.spec.id == newActive } ?: survivors.firstOrNull()
        _installedModels.value = survivors.map { it.copy(isActive = it.spec.id == nextActive?.spec?.id) }
        _status.value =
            when {
                nextActive != null -> LocalModelState.Ready(nextActive.spec, nextActive.file)
                availableModels.isEmpty() && remaining.isEmpty() -> LocalModelState.None
                else -> LocalModelState.NotDownloaded
            }
    }


    fun deleteModel() {
        val active = _installedModels.value.firstOrNull { it.isActive } ?: return
        deleteModel(active.spec.id)
    }

    /** Points the runtime at [modelId] — only among models already installed. */
    fun activate(modelId: String) {
        synchronized(mutationLock) {
            val target = _installedModels.value.firstOrNull { it.spec.id == modelId } ?: return
            val registry = readRegistry()
            writeRegistry(registry.copy(activeId = modelId))
            _status.value = LocalModelState.Ready(target.spec, target.file)
            _installedModels.value =
                _installedModels.value.map { it.copy(isActive = it.spec.id == modelId) }
        }
    }


    fun importModel(
        fileName: String,
        displayName: String,
        open: () -> InputStream?,
    ): Result<Unit> {


        val spec: LocalModelSpec
        synchronized(mutationLock) {
            when (_status.value) {
                is LocalModelState.Downloading ->
                    return Result.failure(IOException("A download is in progress. Cancel it first."))
                is LocalModelState.Importing ->
                    return Result.failure(IOException("An import is already in progress"))
                else -> Unit
            }
            downloadJob?.cancel()
            downloadJob = null
            spec =
                LocalModelSpec(
                    id = "imported-${System.currentTimeMillis()}",
                    displayName = displayName.ifBlank { fileName.substringBeforeLast('.') },
                    family = "Imported",
                    fileName = uniqueImportFileName(fileName),
                    url = "",
                )
            _status.value = LocalModelState.Importing(spec)
            importCancelled = false
        }



        val target = modelFile(spec)
        val part = File(modelsDir, spec.fileName + ".part")
        part.parentFile?.mkdirs()
        return try {
            val input = open() ?: throw IOException("Could not open the selected model file")
            input.use { source ->
                part.outputStream().use { out ->



                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (importCancelled) throw IOException("Import cancelled")
                        val read = source.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            validateModelFile(part)
            finalizeDownload(part, target)
            publishInstalled(spec.id) { registry ->
                registry.copy(
                    imports =
                        registry.imports
                            .filter { it.fileName != spec.fileName }
                            .plus(
                                ImportedModelEntry(
                                    id = spec.id,
                                    displayName = spec.displayName,
                                    fileName = spec.fileName,
                                    sizeBytes = target.length(),
                                ),
                            ),
                )
            }
            synchronized(mutationLock) { _status.value = LocalModelState.Ready(spec, target) }
            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { part.delete() }
            val message = e.message ?: "Import failed"
            synchronized(mutationLock) { _status.value = LocalModelState.Error(message) }
            Result.failure(IOException(message))
        }
    }


    private fun publishInstalled(
        activeId: String,
        transform: (ModelRegistry) -> ModelRegistry,
    ) {
        val registry = transform(readRegistry())
        writeRegistry(registry.copy(activeId = activeId))
        scope.launch { refresh() }
    }

    /** Collisions are impossible in practice; keep the picked file intact if one happens. */
    private fun uniqueImportFileName(original: String): String {
        val safeBase =
            original.substringAfterLast('/')
                .ifBlank { "imported.litertlm" }
        val candidate = File(modelsDir, safeBase)
        if (!candidate.exists() && !candidate.name.endsWith(".part")) return safeBase
        val base = safeBase.substringBeforeLast('.')
        val ext = safeBase.substringAfterLast('.', missingDelimiterValue = "")
        val stamp = System.currentTimeMillis()
        return if (ext.isBlank()) "${base}-${stamp}${safeBase.substringBeforeLast('.').let { "" }}" else "$base-$stamp.$ext"
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

        const val REGISTRY_FILE = "models.json"
    }
}

object OnDeviceModelFormat {

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
