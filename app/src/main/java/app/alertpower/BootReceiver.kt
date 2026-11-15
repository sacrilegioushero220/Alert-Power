package app.alertpower

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the slider service after a reboot or an app update, if it was on before.
 *
 * Pay mode needs nothing here: if the accessibility service was paused by AlertPower when the
 * phone went down ([Prefs.pausedByUs]), the slider service's foreground watcher turns it back
 * on as soon as a non-payment app is in front, and [PaymentPause]'s safety net ends the pause
 * anyway if nothing is watching.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!Prefs.serviceEnabled(context)) return
                try {
                    SliderWakeService.start(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "could not start service", e)
                }
            }
        }
    }
}
