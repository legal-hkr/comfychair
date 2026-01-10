package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.util.DebugLogger

/**
 * Performs maintenance on SharedPreferences to remove stale entries.
 * Cleans up orphaned data from deleted servers and workflows.
 *
 * Should be called on app startup.
 */
object PreferencesMaintenance {
    private const val TAG = "PrefsMaintenance"

    // SharedPreferences file names
    private const val PREFS_WORKFLOW_VALUES = "WorkflowValuesPrefs"
    private const val PREFS_CREDENTIALS = "CredentialPrefs"
    private const val PREFS_TEXT_TO_IMAGE = "TextToImageFragmentPrefs"
    private const val PREFS_IMAGE_TO_IMAGE = "ImageToImageFragmentPrefs"
    private const val PREFS_TEXT_TO_VIDEO = "TextToVideoFragmentPrefs"
    private const val PREFS_IMAGE_TO_VIDEO = "ImageToVideoFragmentPrefs"

    // Credential key prefixes
    private const val KEY_USERNAME_PREFIX = "username_"
    private const val KEY_PASSWORD_PREFIX = "password_"
    private const val KEY_TOKEN_PREFIX = "token_"

    /**
     * Perform full maintenance on all SharedPreferences.
     * Removes entries for deleted servers and workflows.
     *
     * @param context Application context
     */
    fun performMaintenance(context: Context) {
        DebugLogger.i(TAG, "Starting preferences maintenance")

        val validServerIds = getValidServerIds(context)
        val validWorkflowIds = getValidWorkflowIds(context)

        DebugLogger.d(TAG, "Valid servers: ${validServerIds.size}, valid workflows: ${validWorkflowIds.size}")

        var totalRemoved = 0
        totalRemoved += cleanWorkflowValuesPrefs(context, validServerIds, validWorkflowIds)
        totalRemoved += cleanCredentialPrefs(context, validServerIds)
        totalRemoved += cleanScreenPrefs(context, validServerIds, validWorkflowIds)
        totalRemoved += cleanFileCaches(context, validServerIds)

        DebugLogger.i(TAG, "Maintenance complete. Removed $totalRemoved stale entries/files")
    }

    /**
     * Get the set of valid server IDs from ServerStorage.
     */
    private fun getValidServerIds(context: Context): Set<String> {
        return try {
            ServerStorage(context).getServers().map { it.id }.toSet()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to get valid server IDs: ${e.message}")
            emptySet()
        }
    }

    /**
     * Get the set of valid workflow IDs from WorkflowManager.
     */
    private fun getValidWorkflowIds(context: Context): Set<String> {
        return try {
            // Ensure WorkflowManager is initialized
            WorkflowManager.initialize(context)
            WorkflowManager.getAllWorkflows().map { it.id }.toSet()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to get valid workflow IDs: ${e.message}")
            emptySet()
        }
    }

    /**
     * Clean WorkflowValuesPrefs: remove entries for deleted servers or workflows.
     * Key format: {serverId}_{workflowId}
     *
     * @return Number of entries removed
     */
    private fun cleanWorkflowValuesPrefs(
        context: Context,
        validServerIds: Set<String>,
        validWorkflowIds: Set<String>
    ): Int {
        val prefs = context.getSharedPreferences(PREFS_WORKFLOW_VALUES, Context.MODE_PRIVATE)
        val allKeys = prefs.all.keys.toList()
        val keysToRemove = mutableListOf<String>()

        for (key in allKeys) {
            // Key format: {serverId}_{workflowId}
            val underscoreIndex = key.indexOf('_')
            if (underscoreIndex <= 0) continue

            val serverId = key.substring(0, underscoreIndex)
            val workflowId = key.substring(underscoreIndex + 1)

            val serverInvalid = serverId !in validServerIds
            val workflowInvalid = workflowId !in validWorkflowIds

            if (serverInvalid || workflowInvalid) {
                keysToRemove.add(key)
                if (serverInvalid) {
                    DebugLogger.d(TAG, "WorkflowValues: removing key for deleted server: $key")
                } else {
                    DebugLogger.d(TAG, "WorkflowValues: removing key for deleted workflow: $key")
                }
            }
        }

        if (keysToRemove.isNotEmpty()) {
            val editor = prefs.edit()
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
        }

        return keysToRemove.size
    }

    /**
     * Clean CredentialPrefs: remove credentials for deleted servers.
     * Key format: {prefix}_{serverId} where prefix is username_, password_, or token_
     *
     * Note: CredentialPrefs may be encrypted, so we access it directly.
     *
     * @return Number of entries removed
     */
    private fun cleanCredentialPrefs(context: Context, validServerIds: Set<String>): Int {
        // Use CredentialStorage's internal prefs access pattern
        // We need to handle both encrypted and fallback prefs
        val prefs = try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()

            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_CREDENTIALS,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Using fallback prefs for credentials: ${e.message}")
            context.getSharedPreferences(PREFS_CREDENTIALS, Context.MODE_PRIVATE)
        }

        val allKeys = prefs.all.keys.toList()
        val keysToRemove = mutableListOf<String>()

        for (key in allKeys) {
            val serverId = when {
                key.startsWith(KEY_USERNAME_PREFIX) -> key.removePrefix(KEY_USERNAME_PREFIX)
                key.startsWith(KEY_PASSWORD_PREFIX) -> key.removePrefix(KEY_PASSWORD_PREFIX)
                key.startsWith(KEY_TOKEN_PREFIX) -> key.removePrefix(KEY_TOKEN_PREFIX)
                else -> continue
            }

            if (serverId !in validServerIds) {
                keysToRemove.add(key)
                DebugLogger.d(TAG, "Credentials: removing key for deleted server: $key")
            }
        }

        if (keysToRemove.isNotEmpty()) {
            val editor = prefs.edit()
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
        }

        return keysToRemove.size
    }

    /**
     * Clean screen preferences (TTI, ITI, TTV, ITV): remove entries for deleted servers.
     * Key format: {serverId}_{field}
     *
     * Also validates selectedWorkflowId values to ensure they reference existing workflows.
     *
     * @return Number of entries removed
     */
    private fun cleanScreenPrefs(
        context: Context,
        validServerIds: Set<String>,
        validWorkflowIds: Set<String>
    ): Int {
        val screenPrefsNames = listOf(
            PREFS_TEXT_TO_IMAGE,
            PREFS_IMAGE_TO_IMAGE,
            PREFS_TEXT_TO_VIDEO,
            PREFS_IMAGE_TO_VIDEO
        )

        var totalRemoved = 0

        for (prefsName in screenPrefsNames) {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val allEntries = prefs.all.toMap()
            val keysToRemove = mutableListOf<String>()

            for ((key, value) in allEntries) {
                // Key format: {serverId}_{field}
                val underscoreIndex = key.indexOf('_')
                if (underscoreIndex <= 0) continue

                val serverId = key.substring(0, underscoreIndex)
                val field = key.substring(underscoreIndex + 1)

                // Remove if server is deleted
                if (serverId !in validServerIds) {
                    keysToRemove.add(key)
                    DebugLogger.d(TAG, "$prefsName: removing key for deleted server: $key")
                    continue
                }

                // For selectedWorkflowId fields, validate the workflow exists
                if (field == "selectedWorkflowId" || field == "selectedEditingWorkflowId") {
                    val workflowId = value as? String
                    if (workflowId != null && workflowId.isNotEmpty() && workflowId !in validWorkflowIds) {
                        keysToRemove.add(key)
                        DebugLogger.d(TAG, "$prefsName: removing invalid workflow reference: $key -> $workflowId")
                    }
                }
            }

            if (keysToRemove.isNotEmpty()) {
                val editor = prefs.edit()
                keysToRemove.forEach { editor.remove(it) }
                editor.apply()
                totalRemoved += keysToRemove.size
            }
        }

        return totalRemoved
    }

    /**
     * Clean file caches: remove ObjectInfoCache and GalleryMetadataCache files for deleted servers.
     *
     * @return Number of files removed
     */
    private fun cleanFileCaches(context: Context, validServerIds: Set<String>): Int {
        var removed = 0

        // Clean ObjectInfoCache
        val cachedObjectInfoServerIds = ObjectInfoCache.getCachedServerIds(context)
        for (serverId in cachedObjectInfoServerIds) {
            if (serverId !in validServerIds) {
                ObjectInfoCache.clearCache(context, serverId)
                DebugLogger.d(TAG, "ObjectInfoCache: removed cache for deleted server: $serverId")
                removed++
            }
        }

        // Clean GalleryMetadataCache
        // GalleryMetadataCache doesn't have getCachedServerIds, so we scan filesDir manually
        val galleryPrefix = "gallery_metadata_"
        val gallerySuffix = ".json"
        try {
            context.filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(galleryPrefix) && file.name.endsWith(gallerySuffix)) {
                    val serverId = file.name.removePrefix(galleryPrefix).removeSuffix(gallerySuffix)
                    if (serverId !in validServerIds) {
                        GalleryMetadataCache.clearCache(context, serverId)
                        DebugLogger.d(TAG, "GalleryMetadataCache: removed cache for deleted server: $serverId")
                        removed++
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to scan gallery metadata caches: ${e.message}")
        }

        return removed
    }
}
