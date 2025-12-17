package sh.hnet.comfychair.cache

/**
 * An LRU cache that respects priority levels during eviction.
 *
 * Unlike standard LruCache which evicts purely based on LRU order,
 * this cache considers priority levels:
 * - Lower priority number = higher importance (should be kept longer)
 * - Eviction only considers items with equal or lower priority (higher number)
 * - Among evictable items, lowest priority (highest number) items are evicted first
 * - Within the same priority level, LRU order is used
 *
 * This ensures that adjacent items (priority 1) are never evicted to make room
 * for distant items (priority 3), preventing "black screen" issues when scrolling.
 *
 * @param maxSize Maximum size of the cache (in units defined by sizeOf)
 * @param sizeOf Function to calculate size of each entry (defaults to 1 per entry)
 */
class PriorityLruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val sizeOf: (K, V) -> Int = { _, _ -> 1 }
) {
    companion object {
        /** Currently viewed item - highest priority, never evicted */
        const val PRIORITY_CURRENT = 0

        /** Items at index ±1 - very high priority, rarely evicted */
        const val PRIORITY_ADJACENT = 1

        /** Items at index ±2 - high priority */
        const val PRIORITY_NEARBY = 2

        /** Items at index ±3 - medium priority */
        const val PRIORITY_DISTANT = 3

        /** Default priority for items not in the navigation range */
        const val PRIORITY_DEFAULT = 4
    }

    // LinkedHashMap with access-order (true) for LRU behavior
    // When an entry is accessed, it moves to the end (most recently used)
    private val cache = LinkedHashMap<K, V>(16, 0.75f, true)

    // Priority tracking per key
    private val priorities = HashMap<K, Int>()

    // Size tracking per key
    private val sizes = HashMap<K, Int>()

    // Current total size
    private var currentSize = 0

    // Lock for thread safety
    private val lock = Any()

    /**
     * Get a value from the cache.
     * Returns null if not found.
     */
    fun get(key: K): V? = synchronized(lock) {
        cache[key]
    }

    /**
     * Put a value into the cache with a priority.
     *
     * @param key The key
     * @param value The value
     * @param priority The priority level (lower = more important)
     * @return true if the item was added, false if it couldn't be added
     *         (e.g., adding a low-priority item when cache is full of high-priority items)
     */
    fun put(key: K, value: V, priority: Int = PRIORITY_DEFAULT): Boolean = synchronized(lock) {
        val entrySize = sizeOf(key, value)

        // Remove existing entry if present (inline to avoid double synchronization)
        val existingValue = cache.remove(key)
        if (existingValue != null) {
            currentSize -= sizes[key] ?: 0
            priorities.remove(key)
            sizes.remove(key)
        }

        // Make room for the new entry
        // Items can evict other items of same or lower priority (higher number)
        // Among same-priority items, LRU order determines eviction
        val minEvictPriority = priority
        val madeRoom = trimToSize(maxSize - entrySize, minEvictPriority)
        if (!madeRoom) {
            // Couldn't make enough room - all existing items have higher priority
            return false
        }

        // Add the entry
        cache[key] = value
        priorities[key] = priority
        sizes[key] = entrySize
        currentSize += entrySize

        true
    }

    /**
     * Update the priority of an existing entry.
     * Does nothing if the key doesn't exist.
     */
    fun updatePriority(key: K, newPriority: Int): Unit = synchronized(lock) {
        if (cache.containsKey(key)) {
            priorities[key] = newPriority
        }
    }

    /**
     * Update priorities for multiple entries at once.
     * Entries not in the map keep their existing priority.
     */
    fun updateAllPriorities(priorityMap: Map<K, Int>): Unit = synchronized(lock) {
        for ((key, priority) in priorityMap) {
            if (cache.containsKey(key)) {
                priorities[key] = priority
            }
        }
    }

    /**
     * Remove an entry from the cache.
     * Returns the removed value, or null if not found.
     */
    fun remove(key: K): V? = synchronized(lock) {
        val value = cache.remove(key)
        if (value != null) {
            currentSize -= sizes[key] ?: 0
            priorities.remove(key)
            sizes.remove(key)
        }
        value
    }

    /**
     * Clear all entries from the cache.
     */
    fun evictAll(): Unit = synchronized(lock) {
        cache.clear()
        priorities.clear()
        sizes.clear()
        currentSize = 0
    }

    /**
     * Get current size of the cache.
     */
    fun size(): Int = synchronized(lock) { currentSize }

    /**
     * Get maximum size of the cache.
     */
    fun maxSize(): Int = maxSize

    /**
     * Get a snapshot of all keys currently in the cache.
     */
    fun keys(): Set<K> = synchronized(lock) {
        cache.keys.toSet()
    }

    /**
     * Trim the cache to the specified target size.
     * Optimized to find all eviction candidates in one pass (O(n log n) instead of O(n²)).
     *
     * @param targetSize The target size to trim to
     * @param minEvictPriority Only evict items with priority >= this value
     * @return true if we reached the target size, false if we couldn't
     *         (remaining items have higher priority than minEvictPriority)
     */
    private fun trimToSize(targetSize: Int, minEvictPriority: Int): Boolean {
        if (currentSize <= targetSize) return true

        // Collect all evictable candidates in one pass with their LRU order
        val candidates = mutableListOf<EvictionCandidate<K>>()
        var lruOrder = 0
        for (key in cache.keys) {
            val priority = priorities[key] ?: PRIORITY_DEFAULT
            if (priority >= minEvictPriority) {
                candidates.add(EvictionCandidate(key, priority, lruOrder))
            }
            lruOrder++
        }

        if (candidates.isEmpty()) {
            // No evictable candidates - all items have higher priority
            return currentSize <= targetSize
        }

        // Sort by priority descending (lowest priority first), then by LRU order ascending
        candidates.sortWith(compareByDescending<EvictionCandidate<K>> { it.priority }
            .thenBy { it.lruOrder })

        // Evict candidates until we reach target size
        for (candidate in candidates) {
            if (currentSize <= targetSize) break
            evict(candidate.key)
        }

        return currentSize <= targetSize
    }

    /**
     * Helper class for batch eviction sorting
     */
    private data class EvictionCandidate<K>(
        val key: K,
        val priority: Int,
        val lruOrder: Int
    )

    /**
     * Evict a specific key from the cache.
     */
    private fun evict(key: K) {
        cache.remove(key)
        currentSize -= sizes[key] ?: 0
        priorities.remove(key)
        sizes.remove(key)
    }
}
