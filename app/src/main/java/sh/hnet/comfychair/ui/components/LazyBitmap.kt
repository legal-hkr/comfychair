package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey

/**
 * State holder for lazy-loaded bitmaps from MediaCache.
 *
 * @property bitmap The loaded bitmap, or null if not yet loaded or failed
 * @property isLoading Whether the bitmap is currently being loaded
 */
data class LazyBitmapState(
    val bitmap: Bitmap?,
    val isLoading: Boolean
)

/**
 * Remember a lazy-loaded bitmap from MediaCache.
 *
 * This composable handles the common pattern of:
 * 1. Initial synchronous cache lookup
 * 2. Waiting for prefetch if in progress
 * 3. Falling back to direct fetch if needed
 *
 * @param cacheKey The MediaCacheKey to look up
 * @param isVideo Whether this is a video (affects how the bitmap is fetched)
 * @param subfolder The subfolder for fetching
 * @param type The type for fetching (e.g., "output")
 * @return LazyBitmapState with the bitmap and loading state
 */
@Composable
fun rememberLazyBitmap(
    cacheKey: MediaCacheKey,
    isVideo: Boolean,
    subfolder: String,
    type: String
): LazyBitmapState {
    val keyString = cacheKey.keyString

    // State for this specific key - keyed remember ensures fresh state per key
    val state = remember(keyString) {
        // Initial synchronous cache lookup
        val cached = MediaCache.getBitmap(cacheKey)
        mutableStateOf(LazyBitmapState(cached, cached == null))
    }

    // Load bitmap if not in cache
    LaunchedEffect(keyString) {
        if (state.value.bitmap == null) {
            // Wait for prefetch if in progress
            if (MediaCache.isPrefetchInProgress(cacheKey)) {
                MediaCache.awaitPrefetchCompletion(cacheKey)
                val prefetched = MediaCache.getBitmap(cacheKey)
                if (prefetched != null) {
                    state.value = LazyBitmapState(prefetched, false)
                    return@LaunchedEffect
                }
            }

            // Fetch directly
            val bitmap = MediaCache.fetchBitmap(cacheKey, isVideo, subfolder, type)
            state.value = LazyBitmapState(bitmap, false)
        }
    }

    return state.value
}

/**
 * Remember a lazy-loaded bitmap that retains the previous value until a new one is ready.
 *
 * This is useful for UI elements like FABs where you want to avoid showing
 * a loading state when transitioning between items.
 *
 * @param cacheKey The MediaCacheKey to look up, or null to keep the current value
 * @param isVideo Whether this is a video
 * @param subfolder The subfolder for fetching
 * @param type The type for fetching
 * @return The most recent successfully loaded bitmap, or null if none
 */
@Composable
fun rememberRetainedBitmap(
    cacheKey: MediaCacheKey?,
    isVideo: Boolean,
    subfolder: String,
    type: String
): Bitmap? {
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(cacheKey?.keyString) {
        if (cacheKey != null) {
            // Try cache first
            var newBitmap = MediaCache.getBitmap(cacheKey)

            // Wait for prefetch if in progress
            if (newBitmap == null && MediaCache.isPrefetchInProgress(cacheKey)) {
                MediaCache.awaitPrefetchCompletion(cacheKey)
                newBitmap = MediaCache.getBitmap(cacheKey)
            }

            // If still null, fetch directly
            if (newBitmap == null) {
                newBitmap = MediaCache.fetchBitmap(cacheKey, isVideo, subfolder, type)
            }

            // Only update displayed bitmap when we have the new one
            if (newBitmap != null) {
                displayedBitmap = newBitmap
            }
        }
    }

    return displayedBitmap
}
