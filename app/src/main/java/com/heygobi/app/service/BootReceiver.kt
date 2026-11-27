package com.heygobi.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.heygobi.app.security.SecureStore
import com.heygobi.app.voice.WakeWordService

/**
 * Restarts "The Structure" listening after a reboot, but only if setup was
 * completed. If the system does not let the service start from boot, the
 * user just opens the app once — the setup screen offers the same start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = SecureStore(context)
        if (!store.getBoolean(SecureStore.KEY_SETUP_DONE, false)) return
        try {
            WakeWordService.start(context)
        } catch (_: Exception) {
            // The system may refuse a foreground-service start from boot on
            // some builds; the user starts it from the app instead.
        }
    }
}
