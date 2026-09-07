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
            store.refresh()
            assertEquals(LocalModelState.NotDownloaded, store.status.value)

            store.startDownload("gemma-2-2b-it")
            val ready = withTimeout(60_000) { store.status.first { it is LocalModelState.Ready } }
            assertTrue(ready is LocalModelState.Ready)
            val file = (ready as LocalModelState.Ready).file
            assertEquals(200 * 1024, file.length())
            assertTrue(file.name.endsWith(".litertlm"))

            assertTrue(tempDir.listFiles()?.none { it.name.endsWith(".part") } == true)
        }

    @Test
    fun `maps gated or missing downloads to an actionable error`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))

            val store = store()
            store.startDownload("gemma-2-2b-it")
            val error = withTimeout(60_000) { store.status.first { it is LocalModelState.Error } }
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

            val signature = "LITERTLM".toByteArray(Charsets.US_ASCII)
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x41 }
            signature.copyInto(payload)

            val store = store()
            val result = store.importModel("picked.model", "My Gemma") { payload.inputStream() }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertTrue(store.status.value is LocalModelState.Ready)
            val file = (store.status.value as LocalModelState.Ready).file
            assertEquals(payload.size.toLong(), file.length())

            assertTrue(tempDir.listFiles()?.none { it.name.endsWith(".part") } == true)
        }

    @Test
    fun `refresh racing an in-flight import bails instead of clobbering state or the part file`() =
        runBlocking {




            val gate = CountDownLatch(1)
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x41 }
            "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(payload)

            val store = store()
            var importResult: Result<Unit>? = null
            val importThread =
                thread {
                    importResult = store.importModel("picked.model", "My Gemma") { gatedStream(payload, gate) }
                }

            val part =
                withTimeout(60_000) {
                    var found: File? = null
                    while (found == null) {
                        found = tempDir.listFiles()?.firstOrNull { it.name.endsWith(".part") }
                        if (found == null) delay(10)
                    }
                    found
                }
            withTimeout(60_000) {
                while (!(store.status.value is LocalModelState.Importing) || !part.exists()) {
                    delay(10)
                }
            }

            store.refresh()
            assertTrue(store.status.value is LocalModelState.Importing, "refresh clobbered the live state")
            assertTrue(part.exists(), "refresh deleted the .part an import is writing")

            gate.countDown()
            importThread.join(60_000)
            assertTrue(importResult?.isSuccess == true, importResult?.exceptionOrNull()?.message)
            assertTrue(store.status.value is LocalModelState.Ready)
        }

    @Test
    fun `rejects a task container that needs the removed MediaPipe engine`() =
        runBlocking {

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

            val result = store.importModel("bad.task", "Bad file") { ByteArray(5) { 0x00 }.inputStream() }

            assertTrue(result.isFailure)
            val state = store.status.value
            assertTrue(state is LocalModelState.Error, "expected Error, got $state")
            assertTrue((state as LocalModelState.Error).message.contains("not a valid on-device model"))

            assertTrue(tempDir.listFiles()?.isEmpty() != false)
        }

    @Test
    fun `imports add a second library entry alongside the first`() =
        runBlocking {
            val store = store()
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x42 }
            "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(payload)
            val first = store.importModel("a.litertlm", "A") { payload.inputStream() }
            assertTrue(first.isSuccess)



            val secondPayload = payload.copyOf()
            val second = store.importModel("b.litertlm", "B") { secondPayload.inputStream() }
            assertTrue(second.isSuccess, second.exceptionOrNull()?.message)
            store.refresh()
            assertEquals(2, store.installedModels.value.size)

            assertTrue(store.installedModels.value.any { it.spec.displayName == "B" && it.isActive })
            assertTrue(store.installedModels.value.any { it.spec.displayName == "A" && !it.isActive })
        }

    @Test
    fun `deleteModel removes one model and promotes the other to active`() =
        runBlocking {
            val store = store()
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x42 }
            "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(payload)
            store.importModel("a.litertlm", "A") { payload.inputStream() }
            store.importModel("b.litertlm", "B") { payload.inputStream() }
            store.refresh()
            val activeBefore = store.installedModels.value.first { it.isActive }


            val inactive = store.installedModels.value.first { !it.isActive }
            store.deleteModel(inactive.spec.id)
            store.refresh()
            assertEquals(1, store.installedModels.value.size)

            store.deleteModel(activeBefore.spec.id)
            store.refresh()
            assertEquals(0, store.installedModels.value.size)
            assertEquals(LocalModelState.NotDownloaded, store.status.value)
            assertTrue(tempDir.listFiles()?.isEmpty() != false)
        }

    @Test
    fun `activate swaps the ready model without touching disk`() =
        runBlocking {
            val store = store()
            val payload = ByteArray(4 * 1024 * 1024 + 16) { 0x42 }
            "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(payload)
            store.importModel("a.litertlm", "A") { payload.inputStream() }
            store.importModel("b.litertlm", "B") { payload.inputStream() }
            store.refresh()

            val other = store.installedModels.value.first { !it.isActive }
            store.activate(other.spec.id)

            val ready = store.status.value as LocalModelState.Ready
            assertEquals(other.spec.id, ready.model.id)
            assertTrue(store.installedModels.value.first { it.spec.id == other.spec.id }.isActive)
            assertEquals(2, store.installedModels.value.size)
        }

    @Test
    fun `imports record a dynamic display name and byte size`() =
        runBlocking {
            val store = store()
            val payload = ByteArray(4 * 1024 * 1024 + 32) { 0x43 }
            "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(payload)

            store.importModel("my-gemma-finetune.litertlm", "My Gemma Finetune") { payload.inputStream() }
            store.refresh()

            val model = store.installedModels.value.single()
            assertEquals("My Gemma Finetune", model.spec.displayName)
            assertEquals(payload.size.toLong(), model.sizeBytes)
            assertEquals("Imported", model.spec.family)
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
