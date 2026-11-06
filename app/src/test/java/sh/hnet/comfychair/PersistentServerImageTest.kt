package sh.hnet.comfychair

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.cache.*
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.viewmodel.*

class PersistentServerImageTest {
    private fun directory() = Files.createTempDirectory(File(System.getProperty("java.io.tmpdir") ?: error("No scratch directory")).toPath(), "picker-test-").toFile()
    @Test fun recreationHitMissAndNoPlaintextIdentity() {
        val dir = directory()
        try {
            val first = PrivateImageDiskCache(dir)
            val owner = first.namespace(listOf("https://host", "secret-password"))
            first.put(owner, "thumb:private-name", byteArrayOf(1, 2, 3))
            val second = PrivateImageDiskCache(dir)
            assertEquals(owner, second.namespace(listOf("https://host", "secret-password")))
            assertArrayEquals(byteArrayOf(1, 2, 3), second.get(owner, "thumb:private-name", 1000))
            assertNull(second.get(second.namespace(listOf("https://other", "secret-password")), "thumb:private-name", 1000))
            assertNull(second.get(second.namespace(listOf("https://host", "other-account")), "thumb:private-name", 1000))
            assertTrue(dir.listFiles()!!.none { it.name.contains("private-name") || it.readBytes().toString(Charsets.UTF_8).contains("secret-password") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun ttlEvictionAndCorruptionRecovery() {
        val dir = directory(); var now = 1000L
        try {
            val disk = PrivateImageDiskCache(dir, maxBytes = 100, maxEntries = 1, clock = { now })
            disk.put("owner", "one", byteArrayOf(1)); now++
            disk.put("owner", "two", byteArrayOf(2))
            assertNull(disk.get("owner", "one", 100))
            now += 101
            assertNull(disk.get("owner", "two", 100))
            disk.put("owner", "three", byteArrayOf(3))
            dir.listFiles()!!.single { it.extension == "entry" }.writeBytes(byteArrayOf(0))
            assertNull(disk.get("owner", "three", 100))
            disk.put("owner", "three", byteArrayOf(4))
            assertArrayEquals(byteArrayOf(4), disk.get("owner", "three", 100))
            disk.put("owner", "oversized", ByteArray(101))
            assertNull(disk.get("owner", "oversized", 100))
        } finally { dir.deleteRecursively() }
    }
    @Test fun listSourceQuerySortOwnerPartitionAndRecreation() {
        val dir = directory()
        try {
            val page = ServerImagePage(listOf(ServerImage("a.png", "dir", "input", "id")), nextCursor = "next", assets = true, totalAssets = 40)
            val query = ServerImageQuery("中文 +", ServerImageSort.SIZE)
            ServerImageListDiskCache(PrivateImageDiskCache(dir), "one").put(ServerImageSource.INPUT, query, page)
            val cache = ServerImageListDiskCache(PrivateImageDiskCache(dir), "one")
            assertEquals(page, cache.get(ServerImageSource.INPUT, query))
            assertNull(cache.get(ServerImageSource.OUTPUT, query))
            assertNull(cache.get(ServerImageSource.INPUT, query.copy(name = "other")))
            assertNull(cache.get(ServerImageSource.INPUT, query.copy(sort = ServerImageSort.NAME)))
            assertNull(ServerImageListDiskCache(PrivateImageDiskCache(dir), "two").get(ServerImageSource.INPUT, query))
        } finally { dir.deleteRecursively() }
    }
    @Test fun cachedGridSurvivesBackgroundRefreshAndFailure() = runBlocking {
        val owner = ServerImageIdentity("one", Any())
        val old = ServerImage("old.png", "", "input")
        val fresh = ServerImage("new.png", "", "input")
        var gate = CompletableDeferred<ServerImagePage>()
        val picker = ServerImagePicker(this, { owner }, { true }, { _, _, _ -> gate.await() },
            { _, image -> image }, { _, _, _ -> }, cached = { _, _, _ -> ServerImagePage(listOf(old)) })
        picker.open(1, ServerImageSource.INPUT); yield()
        assertEquals(listOf(old), picker.state.value.images)
        assertTrue(picker.state.value.loading)
        gate.complete(ServerImagePage(listOf(fresh))); yield()
        assertEquals(listOf(fresh), picker.state.value.images)
        gate = CompletableDeferred()
        picker.refresh(); yield()
        assertEquals(listOf(fresh), picker.state.value.images)
        gate.completeExceptionally(java.io.IOException("fixture")); yield()
        assertEquals(listOf(fresh), picker.state.value.images)
        assertTrue(picker.state.value.error)
        picker.close()
    }
    @Test fun thumbnailsCacheByDescriptorAndTtl() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port,
                credentials = AuthCredentials.Bearer("fixture")).apply { setWorkingProtocol("http") }
            val image = ServerImage("中文 +.png", "nested", "input")
            server.enqueue(MockResponse().setBody("bytes-one"))
            val first = client.downloadSourcePreview(image)
            assertEquals("bytes-one", first.toString(Charsets.UTF_8))
            val request = server.takeRequest()
            assertEquals("/view", request.requestUrl!!.encodedPath)
            assertEquals("中文 +.png", request.requestUrl!!.queryParameter("filename"))
            assertEquals("nested", request.requestUrl!!.queryParameter("subfolder"))
            assertEquals("webp;60", request.requestUrl!!.queryParameter("preview"))
            server.enqueue(MockResponse().setBody("bytes-two"))
            assertEquals("bytes-two", client.downloadSourcePreview(image).toString(Charsets.UTF_8))
            client.shutdown()
        }
    }
}
