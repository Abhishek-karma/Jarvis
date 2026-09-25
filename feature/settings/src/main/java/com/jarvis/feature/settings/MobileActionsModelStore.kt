package com.jarvis.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads the local MobileActions model into app-private storage. */
@Singleton
class MobileActionsModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    val modelFile: File
        get() = File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > 0L

    fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val target = modelFile
        val partial = File(target.parentFile, "${MODEL_FILE_NAME}.part")
        target.parentFile?.mkdirs()
        partial.delete()

        val request = Request.Builder().url(DOWNLOAD_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Model download failed (${response.code})")
            val body = response.body ?: throw IOException("Model download returned no data")
            val expected = body.contentLength().takeIf { it > 0L } ?: 0L
            var received = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(received, expected)
                    }
                }
            }
            if (partial.length() <= 0L) throw IOException("Model download was empty")
            if (expected > 0L && partial.length() != expected) {
                throw IOException("Model download was incomplete")
            }
            if (target.exists() && !target.delete()) throw IOException("Could not replace the installed model")
            if (!partial.renameTo(target)) throw IOException("Could not install the downloaded model")
        }
    }

    companion object {
        const val MODEL_FILE_NAME = "mobile-actions_q8_ekv1024.litertlm"
        const val DOWNLOAD_URL =
            "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/$MODEL_FILE_NAME?download=true"
    }
}