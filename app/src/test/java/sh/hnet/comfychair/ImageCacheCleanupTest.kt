package sh.hnet.comfychair

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.cache.*

class ImageCacheCleanupTest {
    private fun directory() = Files.createTempDirectory(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")).toPath(), "cleanup-test-").toFile()
    @Test fun preferencesAdapterRoundtripDefaultsAndCorruption() {
        assertEquals(ImageCachePolicy(), ImageCachePolicy.restore { _, default -> default })
        for (automatic in listOf(false, true)) {
            val expected = ImageCachePolicy(automatic, 23, 15)
            val saved = expected.values()
            assertEquals(expected, ImageCachePolicy.restore { key, default -> saved[key] ?: default })
        }
        assertEquals(ImageCachePolicy(), ImageCachePolicy.restore { _, _ -> "corrupt" })
        assertEquals(ImageCachePolicy(), ImageCachePolicy.restore { key, default -> if (key == "image_cache_mb") 0 else default })
    }
    @Test fun deterministicTimestampTies() {
        val dir = directory()
        try {
            var policy = ImageCachePolicy(maxMb = 2)
            val disk = PrivateImageDiskCache(dir, clock = { 1L }, policy = { policy })
            repeat(3) { disk.put("o", "$it", ByteArray(400_000)) }
            val first = dir.listFiles()!!.filter { it.extension == "entry" }.minBy { it.name }
            policy = policy.copy(maxMb = 1); disk.cleanup()
            assertFalse(first.exists()); assertEquals(2, dir.listFiles()!!.count { it.extension == "entry" })
        } finally { dir.deleteRecursively() }
    }
    @Test fun listStartedBeforeClearCannotStoreAfterClear() = runBlocking {
        val owner = sh.hnet.comfychair.viewmodel.ServerImageIdentity("s", Any())
        val gate = CompletableDeferred<sh.hnet.comfychair.model.ServerImagePage>()
        var generation = 0L; var writes = 0
        val picker = sh.hnet.comfychair.viewmodel.ServerImagePicker(this, { owner }, { true },
            { _, _, _ -> gate.await() }, { _, image -> image }, { _, _, _ -> },
            store = { _, _, _, _, _ -> writes++ }, cacheGeneration = { generation })
        picker.open(1, sh.hnet.comfychair.model.ServerImageSource.INPUT); yield()
        generation++
        gate.complete(sh.hnet.comfychair.model.ServerImagePage(emptyList())); yield()
        assertEquals(0, writes); picker.close()
    }
    @Test fun statsCountValidEntriesExactBytesAndExcludeKeyTempCorruptOtherFiles() {
        val dir = directory()
        try {
            val disk = PrivateImageDiskCache(dir)
            assertEquals(PrivateImageDiskCache.Stats(0, 0), disk.stats())
            disk.put("o", "a", ByteArray(100)); disk.put("o", "b", ByteArray(200))
            File(dir, "pending-test.tmp").writeBytes(ByteArray(99))
            File(dir, "materials.json").writeText("keep")
            File(dir, "bad.entry").writeBytes(ByteArray(55))
            assertEquals(PrivateImageDiskCache.Stats(388, 2), disk.stats())
            disk.clear(); assertEquals(PrivateImageDiskCache.Stats(0, 0), disk.stats())
            assertTrue(File(dir, "namespace.key").exists()); assertTrue(File(dir, "materials.json").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun validation() {
        for (mb in listOf("", "0", "1025", "-1", "1.5", "999999999999")) assertNull(ImageCachePolicy.parse(true, mb, "7"))
        for (days in listOf("0", "366", "x")) assertNull(ImageCachePolicy.parse(false, "64", days))
        assertEquals(ImageCachePolicy(false, 1024, 365), ImageCachePolicy.parse(false, "1024", "365"))
    }
    @Test fun ageAloneAndExactBoundary() {
        val dir = directory(); var now = 1L
        try {
            val disk = PrivateImageDiskCache(dir, clock = { now }, policy = { ImageCachePolicy(maxDays = 1) })
            disk.put("o", "old", byteArrayOf(1)); now += 86_400_000
            assertNotNull(disk.get("o", "old", Long.MAX_VALUE))
            now++; disk.put("o", "new", byteArrayOf(2))
            assertNull(disk.get("o", "old", Long.MAX_VALUE)); assertNotNull(disk.get("o", "new", Long.MAX_VALUE))
        } finally { dir.deleteRecursively() }
    }
    @Test fun sizeAloneEvictsCreationOldestNotRecentlyRead() {
        val dir = directory(); var now = 1L
        try {
            val disk = PrivateImageDiskCache(dir, clock = { now }, policy = { ImageCachePolicy(maxMb = 1) })
            disk.put("o", "a", ByteArray(400_000)); now++
            disk.put("o", "b", ByteArray(400_000)); now++
            assertNotNull(disk.get("o", "a", Long.MAX_VALUE))
            disk.put("o", "c", ByteArray(400_000))
            assertNull(disk.get("o", "a", Long.MAX_VALUE)); assertNotNull(disk.get("o", "b", Long.MAX_VALUE))
        } finally { dir.deleteRecursively() }
    }
    @Test fun autoOffPreservesAgeSizeAndFreshnessMissThenLiveEnableAppliesBoth() {
        val dir = directory(); var now = 1L; var policy = ImageCachePolicy(false, 1, 1)
        try {
            val disk = PrivateImageDiskCache(dir, clock = { now }, policy = { policy })
            disk.put("o", "old", ByteArray(600_000)); now += 2 * 86_400_000
            disk.put("o", "a", ByteArray(600_000)); now++; disk.put("o", "b", ByteArray(600_000))
            assertNull(disk.get("o", "old", 1000)); assertEquals(3, dir.listFiles()!!.count { it.extension == "entry" })
            policy = policy.copy(automatic = true); disk.cleanup()
            assertNull(disk.get("o", "old", Long.MAX_VALUE)); assertNull(disk.get("o", "a", Long.MAX_VALUE))
            assertNotNull(disk.get("o", "b", Long.MAX_VALUE))
        } finally { dir.deleteRecursively() }
    }
    @Test fun editedLimitsApplyWithoutRecreation() {
        val dir = directory(); var policy = ImageCachePolicy(maxMb = 4); var now = 1L
        try {
            val disk = PrivateImageDiskCache(dir, clock = { now }, policy = { policy })
            repeat(3) { disk.put("o", "$it", ByteArray(500_000)); now++ }
            policy = policy.copy(maxMb = 1); disk.cleanup()
            assertNull(disk.get("o", "0", Long.MAX_VALUE)); assertNotNull(disk.get("o", "2", Long.MAX_VALUE))
            now += 8 * 86_400_000; policy = policy.copy(maxDays = 1); disk.cleanup()
            assertEquals(0, dir.listFiles()!!.count { it.extension == "entry" })
        } finally { dir.deleteRecursively() }
    }
    @Test fun manualScopeKeyPreservationAndStaleWriters() {
        val dir = directory()
        try {
            val disk = PrivateImageDiskCache(dir, policy = { ImageCachePolicy(false) })
            val owner = disk.namespace(listOf("host", "account")); val secret = File(dir, "namespace.key").readBytes()
            File(dir, "materials.json").writeText("keep"); File(dir, "editing-original.png").writeText("keep")
            disk.put(owner, "thumb:a", byteArrayOf(1)); disk.put(owner, "list:b", byteArrayOf(2)); disk.put(owner, "upload:c", byteArrayOf(3))
            val generation = disk.generation(); disk.clear()
            disk.put(owner, "late", byteArrayOf(4), generation)
            assertEquals(0, dir.listFiles()!!.count { it.extension == "entry" })
            assertArrayEquals(secret, File(dir, "namespace.key").readBytes()); assertEquals(owner, disk.namespace(listOf("host", "account")))
            assertEquals("keep", File(dir, "materials.json").readText()); assertEquals("keep", File(dir, "editing-original.png").readText())
            disk.put(owner, "fresh", byteArrayOf(5)); assertNotNull(disk.get(owner, "fresh", 1000))
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptAndConcurrentReadWriteClear() {
        val dir = directory(); val pool = Executors.newFixedThreadPool(4)
        try {
            val disk = PrivateImageDiskCache(dir, policy = { ImageCachePolicy() })
            disk.put("o", "bad", byteArrayOf(1)); dir.listFiles()!!.single { it.extension == "entry" }.writeBytes(byteArrayOf(0))
            disk.cleanup(); assertNull(disk.get("o", "bad", 1000))
            val futures = (0..3).map { worker -> pool.submit {
                repeat(30) { i -> if (worker == 0) disk.clear() else {
                    val generation = disk.generation(); disk.put("o", "$worker:$i", byteArrayOf(1,2,3), generation)
                    disk.get("o", "$worker:$i", 10000)?.let { assertArrayEquals(byteArrayOf(1,2,3), it) }
                } }
            } }
            futures.forEach { it.get() }; disk.clear()
            assertEquals(0, dir.listFiles()!!.count { it.extension == "entry" || it.extension == "tmp" })
        } finally { pool.shutdownNow(); dir.deleteRecursively() }
    }
}
