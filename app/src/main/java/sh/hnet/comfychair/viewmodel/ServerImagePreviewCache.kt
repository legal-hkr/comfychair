package sh.hnet.comfychair.viewmodel

/** Small, access-ordered, byte-weighted cache. Eviction does not recycle values held by Compose. */
class ServerImagePreviewCache<K, V>(private val maxBytes: Long, private val maxEntries: Int) {
    private val entries = LinkedHashMap<K, Pair<V, Long>>(16, 0.75f, true)
    var bytes: Long = 0
        private set
    val size: Int get() = entries.size
    @Synchronized fun clear() { entries.clear(); bytes = 0 }
    @Synchronized fun get(key: K): V? = entries[key]?.first
    @Synchronized fun put(key: K, value: V, weight: Long) {
        require(weight >= 0)
        entries.remove(key)?.let { bytes -= it.second }
        if (weight > maxBytes || maxEntries <= 0) return
        entries[key] = value to weight
        bytes += weight
        while (bytes > maxBytes || entries.size > maxEntries) {
            val first = entries.entries.iterator()
            bytes -= first.next().value.second
            first.remove()
        }
    }
}
