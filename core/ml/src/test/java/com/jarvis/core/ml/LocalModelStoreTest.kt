package com.jarvis.core.ml

import com.jarvis.core.common.DispatcherProvider
import java.io.ByteArrayInputStream
import java.io.File
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
        spec = LocalModelSpec(
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

    private fun specJson(): String = """
        {"version":1,"models":[
          {
            "id":"gemma-2-2b-it","displayName":"Gemma 2 2B","fileName":"gemma-2-2b-it-gpu-int4.task",
            "url":"${server.url("/gemma.task")}","manualPage":"https://example.com/model-page"
          }
        ]}
    """.trimIndent()

    @Test
    fun `downloads a model and reaches Ready`() = runBlocking {
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
        assertTrue(file.name.endsWith(".task"))
        // No .part residue.
        assertTrue(tempDir.listFiles()?.none { it.name.endsWith(".part") } == true)
    }

    @Test
    fun `maps gated or missing downloads to an actionable error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))

        val store = store()
        store.startDownload("gemma-2-2b-it")
        val error = withTimeout(10_000) { store.status.first { it is LocalModelState.Error } }
        val message = (error as LocalModelState.Error).message
        assertTrue(message.contains("HTTP 404"), message)
        assertTrue(message.contains("models-dev"), message)
    }

    @Test
    fun `copies a dev-asset model instead of downloading`() = runBlocking {
        val payload = ByteArray(64) { 0x42 }
        val store = store(openAsset = { payload.inputStream() })

        store.refresh()
        val ready = withTimeout(5_000) { store.status.first { it is LocalModelState.Ready } }
        assertEquals(64, (ready as LocalModelState.Ready).file.length())
        // Server must never have been hit.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `deleteModel removes the file and resets state`() = runBlocking {
        val payload = ByteArray(64) { 0x42 }
        val store = store(openAsset = { payload.inputStream() })
        store.refresh()
        withTimeout(5_000) { store.status.first { it is LocalModelState.Ready } }

        store.deleteModel()
        assertEquals(LocalModelState.NotDownloaded, store.status.value)
        assertTrue(tempDir.listFiles()?.isEmpty() != false)
    }
}
