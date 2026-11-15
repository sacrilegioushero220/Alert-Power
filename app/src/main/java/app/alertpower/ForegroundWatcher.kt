package app.alertpower

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Reports which app is in the foreground without an accessibility service, by polling
 * UsageStatsManager for ACTIVITY_RESUMED events. Needs "Usage access"
 * (Settings > Apps > Special app access > Usage access, or
 * `adb shell appops set app.alertpower GET_USAGE_STATS allow`).
 *
 * Meant to run only while the screen is on; the owner starts and stops it. Latency is one
 * poll interval, so it cannot beat a payment app's own start-up check; it is the fallback
 * that notices when the payment app is left again. Only package names are read.
 */
class ForegroundWatcher(ctx: Context, private val onTopApp: (String) -> Unit) {

    companion object {
        private const val TAG = "ForegroundWatcher"
        const val POLL_MS = 500L
        private const val SEED_WINDOW_MS = 60_000L
        private const val OVERLAP_MS = 1_500L

        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            }
            return when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT ->
                    ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                        PackageManager.PERMISSION_GRANTED
                else -> false
            }
        }
    }

    private val app = ctx.applicationContext
    private val usage = app.getSystemService(UsageStatsManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTs = 0L
    private var top: String? = null

    var running = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        if (!hasUsageAccess(app)) {
            Log.w(TAG, "usage access not granted, not watching")
            return
        }
        running = true
        lastEventTs = System.currentTimeMillis() - SEED_WINDOW_MS
        top = null
        handler.post(tick)
        Log.i(TAG, "watching")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        Log.i(TAG, "stopped")
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        val events = try {
            usage.queryEvents(lastEventTs, now)
        } catch (e: Exception) {
            Log.e(TAG, "queryEvents failed", e)
            return
        }
        val event = UsageEvents.Event()
        var newest: String? = null
        var newestTs = lastEventTs
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp <= lastEventTs) continue
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (event.timeStamp >= newestTs) {
                newestTs = event.timeStamp
                newest = event.packageName
            }
        }
        // Keep the cursor a little behind "now": an event stamped slightly before this poll can still
        // be appended after it. Older duplicates are skipped by the timestamp check above.
        lastEventTs = maxOf(newestTs, now - OVERLAP_MS)
        if (newest != null && newest != top) {
            top = newest
            onTopApp(newest)
        }
    }
}
