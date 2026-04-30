package sh.hnet.comfychair.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sh.hnet.comfychair.util.DebugLogger

/**
 * Boot receiver to restart the foreground service and schedule
 * WorkManager after device reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            DebugLogger.i(TAG, "Boot completed, scheduling background services")

            // Schedule WorkManager periodic check
            GenerationCheckWorker.schedule(context)

            // Start foreground service if there's pending generation work
            val pendingGen = NotificationHelper.getPendingGeneration(context)
            if (pendingGen != null) {
                DebugLogger.d(TAG, "Found pending generation, starting foreground service")
                GenerationForegroundService.start(context)
            }
        }
    }
}
