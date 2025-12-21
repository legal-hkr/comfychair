package sh.hnet.comfychair.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Log severity levels
 */
enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E")
}

/**
 * A single log entry
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun format(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        return "[$time] ${level.label}/$tag: $message"
    }
}

/**
 * In-memory debug logger singleton.
 * Logs are session-only and cleared when the app closes.
 * Thread-safe implementation using CopyOnWriteArrayList.
 */
object DebugLogger {
    private val entries = CopyOnWriteArrayList<LogEntry>()
    private var enabled = false
    private const val MAX_ENTRIES = 1000

    /**
     * Enable or disable logging.
     * When transitioning from disabled to enabled, clears previous logs and starts fresh.
     */
    fun setEnabled(enabled: Boolean) {
        val wasEnabled = this.enabled
        this.enabled = enabled
        // Only clear and log when transitioning from disabled to enabled
        if (enabled && !wasEnabled) {
            entries.clear()
            log(LogLevel.INFO, "DebugLogger", "Logging started")
        }
    }

    /**
     * Check if logging is enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Log a message at the specified level.
     */
    fun log(level: LogLevel, tag: String, message: String) {
        if (!enabled) return

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        entries.add(entry)

        // Trim old entries if over limit
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    /**
     * Log a DEBUG level message.
     */
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    /**
     * Log an INFO level message.
     */
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)

    /**
     * Log a WARN level message.
     */
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)

    /**
     * Log an ERROR level message.
     */
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    /**
     * Get a copy of all log entries.
     */
    fun getEntries(): List<LogEntry> = entries.toList()

    /**
     * Clear all log entries.
     */
    fun clear() {
        entries.clear()
    }

    /**
     * Export all logs to a formatted string suitable for saving to a file.
     */
    fun exportToString(): String {
        val header = buildString {
            appendLine("=== ComfyChair Debug Log ===")
            appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Entries: ${entries.size}")
            appendLine("===========================")
            appendLine()
        }
        return header + entries.joinToString("\n") { it.format() }
    }
}

/**
 * Utility object for obfuscating sensitive data in log messages.
 * Replaces actual values with safe placeholders to protect user privacy.
 */
object Obfuscator {
    /**
     * Obfuscate a prompt text. Shows only character count.
     */
    fun prompt(text: String?): String =
        if (text.isNullOrBlank()) "<empty>"
        else "<prompt:${text.length}chars>"

    /**
     * Obfuscate a hostname/server address.
     */
    fun hostname(host: String?): String = "<server>"

    /**
     * Obfuscate a filename. Shows only the file extension.
     */
    fun filename(name: String?): String =
        name?.let {
            val ext = it.substringAfterLast('.', "")
            if (ext.isNotEmpty()) "<file:.$ext>" else "<file>"
        } ?: "<file>"

    /**
     * Obfuscate a prompt ID. Shows only the first 8 characters.
     */
    fun promptId(id: String?): String =
        id?.take(8)?.plus("...") ?: "<unknown>"

    /**
     * Obfuscate a file path.
     */
    fun path(path: String?): String = "<path>"

    /**
     * Obfuscate a model name. Shows only the filename portion.
     */
    fun modelName(name: String?): String =
        name?.substringAfterLast('/')?.substringAfterLast('\\') ?: "<model>"
}
