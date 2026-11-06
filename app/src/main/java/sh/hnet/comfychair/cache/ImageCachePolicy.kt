package sh.hnet.comfychair.cache

/** Retention is absolute creation age, not last access. MB in the UI means MiB. */
data class ImageCachePolicy(val automatic: Boolean = true, val maxMb: Int = 64, val maxDays: Int = 7) {
    init { require(maxMb in 1..1024); require(maxDays in 1..365) }
    val maxBytes: Long get() = maxMb * 1024L * 1024L
    val maxAgeMillis: Long get() = maxDays * 86_400_000L
    fun values(): Map<String, Any> = mapOf("image_cache_auto" to automatic, "image_cache_mb" to maxMb, "image_cache_days" to maxDays)
    companion object {
        fun restore(read: (String, Any) -> Any): ImageCachePolicy = runCatching {
            ImageCachePolicy(read("image_cache_auto", true) as Boolean,
                read("image_cache_mb", 64) as Int, read("image_cache_days", 7) as Int)
        }.getOrDefault(ImageCachePolicy())
        fun parse(automatic: Boolean, mb: String, days: String): ImageCachePolicy? =
            runCatching { ImageCachePolicy(automatic, mb.toInt(), days.toInt()) }.getOrNull()
    }
}
