package app.alertpower

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Turns the display on the same way a power-button press would: the lock screen
 * appears and nothing is unlocked.
 *
 * Primary path: FULL_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP. It is deprecated but is
 * still the only public API that lights the screen from a background service.
 *
 * Fallback: [WakeActivity] with turnScreenOn / showWhenLocked, tried only if the screen
 * is still off shortly after the wake lock. Launching it from the background needs the
 * "Display over other apps" permission, which setup offers as optional.
 */
object ScreenWaker {
    private const val TAG = "ScreenWaker"
    private const val HOLD_MS = 3_000L
    private const val MIN_INTERVAL_MS = 400L
    private const val FALLBACK_CHECK_MS = 700L

    private val handler = Handler(Looper.getMainLooper())
    private var lastWake = 0L

    fun isScreenOn(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    fun wake(ctx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWake < MIN_INTERVAL_MS) return
        lastWake = now

        val app = ctx.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        Log.i(TAG, "wake requested: $reason")

        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK
                or PowerManager.ACQUIRE_CAUSES_WAKEUP
                or PowerManager.ON_AFTER_RELEASE,
            "alertpower:wake"
        )
        wl.acquire(HOLD_MS)

        handler.postDelayed({
            if (pm.isInteractive) {
                Log.i(TAG, "screen is on")
                return@postDelayed
            }
            Log.w(TAG, "wake lock did not light the screen, trying activity fallback")
            try {
                app.startActivity(
                    Intent(app, WakeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (e: Exception) {
                Log.e(TAG, "activity fallback failed", e)
            }
        }, FALLBACK_CHECK_MS)
    }
}
