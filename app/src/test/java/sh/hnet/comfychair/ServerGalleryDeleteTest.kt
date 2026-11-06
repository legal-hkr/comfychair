package sh.hnet.comfychair

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.connection.ServerMediaDelete
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.cache.*
import java.nio.file.Files
import java.io.File

class ServerGalleryDeleteTest {
    @Test fun prepareDeleteCarryOnlyDedicatedKeyAndManifestNoRedirect() = runBlocking {
        MockWebServer().use { s ->
            val api = ServerMediaDelete(OkHttpClient(), s.url("/"), "separate-key")
            s.enqueue(MockResponse().setBody("""{"version":1,"delete":true,"max_batch":50}"""))
            assertTrue(api.available())
            assertEquals("separate-key", s.takeRequest().getHeader("X-ComfyChair-Delete"))
            s.enqueue(MockResponse().setBody("""{"token":"abcdefghijklmnopqrstuvwxyz0123456789","items":[{"index":0,"status":"ready"},{"index":1,"status":"not_found"}]}"""))
            val manifest = api.prepare(listOf(ServerImage("中文 +.png", "folder", "input"), ServerImage("b.png", "", "input")))
            val request = s.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("中文 +.png"))
            s.enqueue(MockResponse().setBody("""{"items":[{"index":0,"status":"success"},{"index":1,"status":"not_found"}]}"""))
            assertEquals(listOf("success", "not_found"), api.delete(manifest).map { it.status })
            val deletion = s.takeRequest()
            assertEquals("/comfychair/v1/media/delete", deletion.path)
            val payload = deletion.body.readUtf8()
            assertTrue(payload.contains(manifest.token)); assertFalse(payload.contains("filename"))
        }
    }
    @Test fun redirectsAndAuthErrorsFailClosed() = runBlocking {
        MockWebServer().use { s -> MockWebServer().use { other ->
            val api = ServerMediaDelete(OkHttpClient(), s.url("/"), "secret")
            for (status in listOf(302, 401, 403, 500)) {
                s.enqueue(MockResponse().setResponseCode(status).setHeader("Location", other.url("/steal")))
                assertTrue(runCatching { api.available() }.isFailure)
            }
            assertEquals(0, other.requestCount); assertEquals(4, s.requestCount)
        } }
    }
    @Test fun malformedAndOversizedResultsNeverBecomeSuccess() = runBlocking {
        MockWebServer().use { s ->
            val api = ServerMediaDelete(OkHttpClient(), s.url("/"), "secret")
            s.enqueue(MockResponse().setBody("""{"token":"abcdefghijklmnopqrstuvwxyz0123456789","items":[{"index":7,"status":"success"}]}"""))
            assertTrue(runCatching { api.prepare(listOf(ServerImage("a.png", "", "input"))) }.isFailure)
            s.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
            assertTrue(runCatching { api.available() }.isFailure)
            assertTrue(runCatching { api.prepare(List(51) { ServerImage("a.png", "", "input") }) }.isFailure)
            assertEquals(2, s.requestCount)
        }
    }
    @Test fun targetedCacheInvalidationRetainsUploadAndOtherSourceAndRejectsLateWrites() {
        val root = Files.createTempDirectory("delete-cache").toFile()
        try {
            val disk = PrivateImageDiskCache(root)
            val list = ServerImageListDiskCache(disk, "owner")
            val image = ServerImage("a.png", "", "input")
            list.put(ServerImageSource.INPUT, ServerImageQuery(), ServerImagePage(listOf(image)))
            list.put(ServerImageSource.OUTPUT, ServerImageQuery(), ServerImagePage(listOf(image.copy(type = "output"))))
            disk.put("owner", "upload:immutable", byteArrayOf(1))
            disk.put("owner", "thumb:" + image.stableKey, byteArrayOf(2))
            val generation = disk.generation()
            disk.invalidateImages("owner", listOf(image))
            assertNull(list.get(ServerImageSource.INPUT, ServerImageQuery()))
            assertNotNull(list.get(ServerImageSource.OUTPUT, ServerImageQuery()))
            assertArrayEquals(byteArrayOf(1), disk.get("owner", "upload:immutable", 1000))
            assertNull(disk.get("owner", "thumb:" + image.stableKey, 1000))
            disk.put("owner", "thumb:" + image.stableKey, byteArrayOf(3), generation)
            assertNull(disk.get("owner", "thumb:" + image.stableKey, 1000))
        } finally { root.deleteRecursively() }
    }
    @Test fun galleryWithoutDedicatedKeyDoesNotRequestPrivateMissingMetadata() = runBlocking {
        MockWebServer().use { s ->
            val c = ComfyUIClient(hostname = s.hostName, port = s.port).apply { setWorkingProtocol("http") }
            s.enqueue(MockResponse().setBody("""{"version":1,"delete":false,"max_batch":50}"""))
            s.enqueue(MockResponse().setBody("""{"assets":false}"""))
            s.enqueue(MockResponse().setBody("""{"LoadImage":{"input":{"required":{"image":[["a.png","b.png"]]}}}}"""))
            val page = c.browseSourceImages(ServerImageSource.INPUT, ServerImageQuery())
            assertEquals(listOf("a.png", "b.png"), page.images.map { it.filename })
            repeat(3) { val r = s.takeRequest(); assertEquals("GET", r.method); assertFalse(r.path.orEmpty().contains("/missing")) }
        }
    }
    @Test fun deletionKeyNeverSentToRemotePlainHttp() = runBlocking {
        val api = ServerMediaDelete(OkHttpClient(), okhttp3.HttpUrl.Builder().scheme("http").host("192.168.4.36").port(8189).build(), "secret")
        assertTrue(runCatching { api.available() }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun generationSheetsUseViewModelLoadOriginalWithIdentityGuard() {
        // The generation screens' server picker hands the picked original through the shared view model's
        // identity-guarded loader, not through per-screen bitmap plumbing.
        val model = File("src/main/java/sh/hnet/comfychair/viewmodel/ServerGalleryViewModel.kt").readText()
        val loader = model.substringAfter("suspend fun loadOriginal").substringBefore("fun loadOriginalIn")
        assertTrue(loader.contains("currentMediaIdentity() == owner"))
        val screen = File("src/main/java/sh/hnet/comfychair/ui/screens/ImageToImageScreen.kt").readText()
        assertTrue(screen.contains("ServerImagePickerSheet("))
        assertTrue(screen.contains("imageToImageViewModel.useAsSource(context, bitmap)"))
    }
    @Test fun galleryTabViewerSourceAndSecureStorageWiring() {
        fun source(p: String) = File("src/main/java/sh/hnet/comfychair/$p").readText()
        // The server gallery lives in the Gallery tab (not a Settings route) and opens originals in the shared viewer
        val gallery = source("ui/screens/GalleryScreen.kt")
        for (s in listOf("ServerGalleryTabContent(", "GalleryViewDropdown", "rememberCoroutineScope",
            "if (showServer) serverSelecting else uiState.isSelectionMode")) assertTrue(s, gallery.contains(s))
        val screen = source("ui/screens/ServerGalleryTabContent.kt")
        assertTrue(screen.contains("ServerGalleryGrid("))
        assertTrue(screen.contains("selectedImages = selected"))
        for (s in listOf("openImage(", "DeletionCredentialStorage", "viewModel.open(source)")) assertTrue(s, screen.contains(s))
        val storage = source("storage/DeletionCredentialStorage.kt")
        assertTrue(storage.contains("EncryptedSharedPreferences")); assertFalse(storage.contains("getSharedPreferences("))
        assertFalse(screen.contains("rememberSaveable(\n        secret"))
    }
}
