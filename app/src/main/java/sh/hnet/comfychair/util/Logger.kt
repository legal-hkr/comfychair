package sh.hnet.comfychair.util

import android.util.Log

/**
 * Centralized logging utility for the ComfyChair app.
 * Uses Android's Log class with a consistent tag prefix.
 */
object Logger {
    private const val TAG_PREFIX = "ComfyChair"

    /**
     * Log an error message with optional throwable.
     *
     * @param tag Subtag for identifying the source (e.g., "MediaCache", "GalleryRepo")
     * @param message The error message
     * @param throwable Optional exception to include in the log
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$TAG_PREFIX:$tag", message, throwable)
        } else {
            Log.e("$TAG_PREFIX:$tag", message)
        }
    }

    /**
     * Log a warning message.
     *
     * @param tag Subtag for identifying the source
     * @param message The warning message
     */
    fun w(tag: String, message: String) {
        Log.w("$TAG_PREFIX:$tag", message)
    }

    /**
     * Log a debug message.
     *
     * @param tag Subtag for identifying the source
     * @param message The debug message
     */
    fun d(tag: String, message: String) {
        Log.d("$TAG_PREFIX:$tag", message)
    }

    /**
     * Log an info message.
     *
     * @param tag Subtag for identifying the source
     * @param message The info message
     */
    fun i(tag: String, message: String) {
        Log.i("$TAG_PREFIX:$tag", message)
    }
}
