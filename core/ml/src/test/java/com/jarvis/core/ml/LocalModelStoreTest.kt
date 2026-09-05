package com.jarvis.core.ml

import com.jarvis.core.common.DispatcherProvider
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/**
 * Store logic tests: real files in a @TempDir and a real OkHttp client against MockWebServer
 * (downloads run on Dispatchers.IO; assertions poll the status flow with timeouts).
 */
class LocalModelStoreTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var server: MockWebServer
    private lateinit var spec: LocalModelSpec

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        spec =
            LocalModelSpec(
                id = "gemma-2-2b-it",
                displayName = "Gemma 2 2B",
                fileName = "gemma-2-2b-it-gpu-int4.task",
                url = server.url("/gemma.task").toString(),
                manualPage = "https://example.com/model-page",
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun store(openAsset: (String) -> ByteArrayInputStream? = { null }): LocalModelStore =
        LocalModelStore(
            catalog = LocalModelCatalog(source = { ByteArrayInputStream(specJson().toByteArray()) }),
            modelsDir = tempDir,
            openAsset = openAsset,
            okHttpClient = OkHttpClient(),
            dispatchers = DispatcherProvider(),
        )

    private fun specJson(): String =
        """
        {"version":1,"models":[
          {
            "id":"gemma-2-2b-it","displayName":"Gemma 2 2B","fileName":"gemma-2-2b-it-gpu-int4.litertlm",
            "url":"${server.url("/gemma.task")}","manualPage":"https://example.com/model-page"
          }
        ]}
        """.trimIndent()

    @Test
    fun `downloads a model and reaches Ready`() =
        runBlocking {
            val body = ByteArray(200 * 1024) { 0x41 }
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

            val store = store()
            store.refresh() // settle the async init refresh
            assertEquals(LocalModelState.NotDownloaded, store.status.value)

            store.startDownload("gemma-2-2b-it")
            val ready = withTimeout(10_000) { store.status.first { it is LocalModelState.Ready } }
            assertTrue(ready is LocalModelState.Ready)
            val file = (ready as LocalModelState.Ready).file
            assertEquals(200 * 1024, file.length())
            assertTrue(file.name.endsWith(".litertlm"))
            // No .part residue.
            assertTrue(tempDir.listFiles()?.none { it.name.endsWith(".part") } == true)
        }

    @Test
    fun `maps gated or missing downloads to an actionable error`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))

            val store = store()
            store.startDownload("gemma-2-2b-it")
            val error = withTimeout(10_000) { store.status.first { it is LocalModelState.Error } }
            val message = (error as LocalModelState.Error).message
            assertTrue(message.contains("HTTP 404"), message)
            assertTrue(message.contains("models-dev"), message)
        }

    @Test
    fun `copies a dev-asset model instead of downloading`() =
        runBlocking {
            val payload = ByteArray(64) { 0x42 }
            val store = store(openAsset = { payload.inputStream() })

            store.refresh()
            val ready = withTimeout(5_000) { store.status.first { it is LocalModelState.Ready } }
            assertEquals(64, (ready as LocalModelState.Ready).file.length())
            // Server must never have been hit.
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `deleteModel removes the file and resets state`() =
        runBlocking {
            val payload = ByteArray(64) { 0x42 }
            val store = store(openAsset = { payload.inputStream() })
            store.refresh()
            withTimeout(5_000) { store.status.first { it is LocalModelState.Ready } }

            store.deleteModel()
            assertEquals(LocalModelState.NotDownloaded, store.status.value)
            assertTrue(tempDir.listFiles()?.isEmpty() != false)
        }

    @Test
    fun `imports a real litertlm container`() =
        runBlocking {
            // Real signature from litert-community bundles: the .litertlm container (literal "LITERTLM").
            val signature = "LITERTLM".toByteArray(Charsets.US_ASCII)
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x41 }
            signature.copyInto(payload)

            val store = store()
            val result = store.importModel("picked.model", "My Gemma") { payload.inputStream() }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertTrue(store.status.value is LocalModelState.Ready)
            val file = (store.status.value as LocalModelState.Ready).file
            assertEquals(payload.size.toLong(), file.length())
            // No .part residue under the final name.
            assertTrue(tempDir.listFiles()?.none { it.name.endsWith(".part") } == true)
        }

    @Test
    fun `refresh racing an in-flight import bails instead of clobbering state or the part file`() =
        runBlocking {
            // Gate the import's read so it is parked inside the copy: state is Importing and
            // the .part exists. A refresh landing in that window must bail — before the fix it
            // deleted the .part (unlinks a live file on Linux, fails silently on Windows) and
            // clobbered the live state with NotDownloaded.
            val gate = CountDownLatch(1)
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x41 }
            "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(payload)

            val store = store()
            var importResult: Result<Unit>? = null
            val importThread =
                thread {
                    importResult = store.importModel("picked.model", "My Gemma") { gatedStream(payload, gate) }
                }
            val part = File(tempDir, "gemma-2-2b-it-gpu-int4.litertlm.part")
            withTimeout(10_000) {
                while (!(store.status.value is LocalModelState.Importing) || !part.exists()) {
                    delay(10)
                }
            }

            store.refresh()
            assertTrue(store.status.value is LocalModelState.Importing, "refresh clobbered the live state")
            assertTrue(part.exists(), "refresh deleted the .part an import is writing")

            gate.countDown()
            importThread.join(10_000)
            assertTrue(importResult?.isSuccess == true, importResult?.exceptionOrNull()?.message)
            assertTrue(store.status.value is LocalModelState.Ready)
        }

    @Test
    fun `rejects a task container that needs the removed MediaPipe engine`() =
        runBlocking {
            // A TFLite flatbuffer .task (size prefix + "TFL3") is no longer a supported container.
            val signature =
                byteArrayOf(
                    0x1c, 0, 0, 0,
                    'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte(),
                )
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x41 }
            signature.copyInto(payload)

            val store = store()
            val result = store.importModel("picked.task", "Bad file") { payload.inputStream() }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("isn't a supported on-device model bundle") == true)
        }

    @Test
    fun `rejects an invalid import with Error state and no leftover file`() =
        runBlocking {
            val store = store()
            // Too small to be a real bundle: validation must reject it before Ready.
            val result = store.importModel("bad.task", "Bad file") { ByteArray(5) { 0x00 }.inputStream() }

            assertTrue(result.isFailure)
            val state = store.status.value
            assertTrue(state is LocalModelState.Error, "expected Error, got $state")
            assertTrue((state as LocalModelState.Error).message.contains("not a valid on-device model"))
            // Both the target and the .part must be gone — refresh() must never see a corrupt file.
            assertTrue(tempDir.listFiles()?.isEmpty() != false)
        }

    @Test
    fun `refuses an import while a model is already installed`() =
        runBlocking {
            val store = store()
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x42 }
            "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(payload)
            val first = store.importModel("a.litertlm", "A") { payload.inputStream() }
            assertTrue(first.isSuccess)

            // importModel is synchronous, so a second call arrives after the first finished:
            // it must be refused because a model is already installed.
            val second = store.importModel("b.litertlm", "B") { ByteArrayInputStream(ByteArray(8) { 0x00 }) }
            assertTrue(second.isFailure)
            assertTrue(second.exceptionOrNull()?.message?.contains("already installed") == true)
        }

    /** Streams [payload] but blocks every read until [gate] is released. */
    private fun gatedStream(payload: ByteArray, gate: CountDownLatch): InputStream =
        object : InputStream() {
            private val delegate = payload.inputStream()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                gate.await()
                return delegate.read(b, off, len)
            }

            override fun read(): Int = delegate.read()
        }
}
