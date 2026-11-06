package sh.hnet.comfychair.cache

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** App-private no-backup cache. Keys are HMACs, never URLs, account names or credentials.
 * Freshness TTL is independent of retention/oldest-created eviction. Process-wide IO lock.
 */
class PrivateImageDiskCache(private val root: File, private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxEntries: Int = 512, private val clock: () -> Long = System::currentTimeMillis,
    private val policy: (() -> ImageCachePolicy)? = null) {
    companion object { private val lock = Any(); const val MAX_ENTRY = 4 * 1024 * 1024 }
    @Volatile private var revision = 0L
    fun generation(): Long = revision
    /** Serialized with reads/publication; retain the installation HMAC key and unrelated files. */
    fun clear() = synchronized(lock) {
        revision++
        val files = root.listFiles() ?: if (root.exists()) throw IOException("Cannot list cache") else emptyArray()
        files.filter { it.extension == "entry" || (it.name.startsWith("pending-") && it.extension == "tmp") }
            .forEach { if (!it.delete() && it.exists()) throw IOException("Cannot clear cache entry") }
    }
    data class Stats(val bytes: Long, val entries: Int)
    /** Count only checksum-valid entries; no namespace key, pending files or unrelated files. */
    fun stats(): Stats = synchronized(lock) {
        val files = root.listFiles() ?: if (root.exists()) throw IOException("Cannot inspect cache") else emptyArray()
        var bytes = 0L; var count = 0
        files.filter { it.extension == "entry" }.forEach { f ->
            if (!f.canRead()) throw IOException("Cannot read cache entry")
            val valid = runCatching {
                require(f.length() in 44L..(MAX_ENTRY + 44L))
                java.io.DataInputStream(f.inputStream().buffered()).use { input ->
                    require(input.readInt() == 1); val created = input.readLong()
                    require(created in 0..clock())
                    val checksum = ByteArray(32).also { input.readFully(it) }
                    require(MessageDigest.isEqual(checksum, digest(input.readBytes())))
                }
            }.isSuccess
            if (valid) { bytes += f.length(); count++ }
        }
        Stats(bytes, count)
    }
    fun cleanup() = synchronized(lock) { cleanupLocked() }
    private fun created(file: File): Long? = runCatching {
        java.io.DataInputStream(file.inputStream().buffered()).use {
            require(file.length() in 44L..(MAX_ENTRY + 44L)); require(it.readInt() == 1); it.readLong()
        }
    }.getOrNull()
    private fun cleanupLocked() {
        val setting = policy?.invoke()
        val now = clock()
        val files = root.listFiles()?.filter { it.extension == "entry" }.orEmpty()
        val valid = files.mapNotNull { f ->
            val time = created(f)
            if (time == null || time > now || (setting?.automatic == true && now - time > setting.maxAgeMillis)) {
                f.delete(); null
            } else f to time
        }.sortedWith(compareBy<Pair<File, Long>> { it.second }.thenBy { it.first.name })
        // Auto OFF disables user age/size limits. Independent safety ceiling remains explicit.
        val byteLimit = if (setting == null) maxBytes else if (setting.automatic) setting.maxBytes else 1024L * 1024 * 1024
        val entryLimit = if (setting == null) maxEntries else 8192
        var size = valid.sumOf { it.first.length() }; var count = valid.size
        for ((f, _) in valid) {
            if (size <= byteLimit && count <= entryLimit) break
            val length = f.length()
            if (f.delete()) { size -= length; count-- }
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun key(parts: List<String>): String {
        root.mkdirs()
        val secretFile = File(root, "namespace.key")
        val secret = if (secretFile.isFile && secretFile.length() == 32L) secretFile.readBytes() else {
            // Loss/corruption of the installation key invalidates all old namespaces.
            root.listFiles()?.filter { it.name != "namespace.key" }?.forEach { it.delete() }
            ByteArray(32).also { SecureRandom().nextBytes(it); secretFile.writeBytes(it) }
        }
        val encoded = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return hex(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(secret, "HmacSHA256")); doFinal(encoded) })
    }
    fun namespace(parts: List<String>): String = synchronized(lock) { key(parts) }
    private fun file(owner: String, id: String) = File(root, key(listOf(owner, id)) + ".entry")
    fun get(owner: String, id: String, ttlMillis: Long): ByteArray? = synchronized(lock) {
        try {
            cleanupLocked()
            val f = file(owner, id)
            if (!f.isFile) return@synchronized null
            try {
                require(f.length() in 44L..(MAX_ENTRY + 44L))
                val value = f.inputStream().buffered().use { stream ->
                    val input = java.io.DataInputStream(stream)
                    require(input.readInt() == 1)
                    val age = clock() - input.readLong()
                    require(age >= 0)
                    if (age > ttlMillis && policy != null) return@synchronized null
                    require(age <= ttlMillis)
                    val checksum = ByteArray(32).also { input.readFully(it) }
                    val bytes = input.readBytes()
                    require(MessageDigest.isEqual(checksum, digest(bytes)))
                    bytes
                }
                f.setLastModified(clock())
                value
            } catch (_: Exception) { f.delete(); null }
        } catch (_: IOException) { null }
    }
    fun listVersion(owner: String, source: String): String = synchronized(lock) {
        val f = File(root, key(listOf(owner, "list-version:$source")) + ".revision")
        if (f.exists()) f.readText().take(64) else ""
    }
    fun invalidateImages(owner: String, images: List<sh.hnet.comfychair.model.ServerImage>) = synchronized(lock) {
        revision++ // Reject in-flight stale publication, without removing unrelated cache data.
        images.forEach { remove(owner, "thumb:" + it.stableKey) }
        images.map { it.type }.distinct().forEach { source ->
            val target = File(root, key(listOf(owner, "list-version:$source")) + ".revision")
            val temp = File.createTempFile("pending-", ".tmp", root)
            try {
                temp.writeText(java.util.UUID.randomUUID().toString())
                check(temp.renameTo(target))
            } finally { temp.delete() }
        }
    }
    fun remove(owner: String, id: String) = synchronized(lock) { runCatching { file(owner, id).delete() }; Unit }
    fun put(owner: String, id: String, bytes: ByteArray, expectedGeneration: Long = generation()) = synchronized(lock) {
        if (expectedGeneration != revision || bytes.size > MAX_ENTRY ||
            (policy == null && (bytes.size + 44 > maxBytes || maxEntries <= 0))) return@synchronized
        var temporary: File? = null
        try {
            val target = file(owner, id)
            temporary = File.createTempFile("pending-", ".tmp", root)
            java.io.DataOutputStream(temporary.outputStream().buffered()).use {
                it.writeInt(1); it.writeLong(clock()); it.write(digest(bytes)); it.write(bytes)
            }
            if (!temporary.renameTo(target)) throw IOException("Cache publication failed")
            cleanupLocked()
        } catch (_: IOException) { /* Best effort: cache failure must not prevent original operation. */ }
        finally { temporary?.delete() }
    }
}
