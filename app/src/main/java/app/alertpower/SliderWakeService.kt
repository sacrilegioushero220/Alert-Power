package app.alertpower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that turns the OnePlus alert slider into the power button.
 *
 * The slider is a hardware key the kernel treats as a wake source, so moving it wakes
 * the SoC even from deep sleep. OxygenOS then changes the ringer mode and/or the
 * Do-Not-Disturb filter, which fires RINGER_MODE_CHANGED / INTERRUPTION_FILTER_CHANGED.
 * This service counts those moves:
 *
 *   screen off, any move                 -> wake (then ignore moves for a grace period
 *                                           so the slider can be returned)
 *   screen on, 1 move                    -> nothing, the ringer change is left alone
 *   screen on, 2 moves (flick + return)  -> screen off
 *   screen on, 3+ moves                  -> power menu
 *
 * A "gesture" ends when no move arrives for GESTURE_SETTLE_MS.
 */
class SliderWakeService : Service() {

    companion object {
        private const val TAG = "SliderWakeService"
        const val CHANNEL_ID = "alertpower_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "app.alertpower.action.START"
        const val ACTION_STOP = "app.alertpower.action.STOP"
        const val ACTION_TEST_WAKE = "app.alertpower.action.TEST_WAKE"

        /** Two broadcasts closer than this are one physical move. */
        private const val MIN_MOVE_SPACING_MS = 150L
        /** Quiet time that ends a gesture. */
        private const val GESTURE_SETTLE_MS = 1_200L
        /** After a wake, moves are ignored this long so the slider can be flicked back. */
        private const val POST_WAKE_GRACE_MS = 2_500L
        private const val TEST_DELAY_MS = 8_000L

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, SliderWakeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SliderWakeService::class.java).setAction(ACTION_STOP))
        }

        fun testWake(ctx: Context) {
            ctx.startService(Intent(ctx, SliderWakeService::class.java).setAction(ACTION_TEST_WAKE))
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** (ringerMode, interruptionFilter) last seen. A move is a change of this pair. */
    private var lastState: Pair<Int, Int>? = null
    private var lastMoveAt = 0L
    private var moveCount = 0
    private var ignoreMovesUntil = 0L
    private var testWakeLock: PowerManager.WakeLock? = null

    private val settleRunnable = Runnable { onGestureSettled() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // RINGER_MODE_CHANGED is sticky: the current value is replayed at registration.
            if (isInitialStickyBroadcast) return
            when (intent.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION,
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> onSliderBroadcast()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lastState = currentState()
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        // Both actions are protected system broadcasts, so exporting the receiver is safe.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        running = true
        Log.i(TAG, "started, initial state=$lastState")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setServiceEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_WAKE -> runTestWake()
            else -> Prefs.setServiceEnabled(this, true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        testWakeLock?.let { if (it.isHeld) it.release() }
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentState(): Pair<Int, Int> {
        val am = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        return am.ringerMode to nm.currentInterruptionFilter
    }

    /** Collapses the one-or-two broadcasts OxygenOS sends per slider move into one move. */
    private fun onSliderBroadcast() {
        val now = SystemClock.elapsedRealtime()
        val state = currentState()
        val prev = lastState
        lastState = state
        if (prev == null || state == prev) return                 // duplicate for the same move
        if (now - lastMoveAt < MIN_MOVE_SPACING_MS) return       // ringer + DND racing for one move
        lastMoveAt = now
        onSliderMoved(now, prev, state)
    }

    private fun onSliderMoved(now: Long, from: Pair<Int, Int>, to: Pair<Int, Int>) {
        if (now < ignoreMovesUntil) {
            Log.d(TAG, "move $from->$to ignored, inside post-wake grace")
            return
        }

        if (!ScreenWaker.isScreenOn(this)) {
            moveCount = 0
            handler.removeCallbacks(settleRunnable)
            if (!Prefs.wakeOnSlider(this)) return
            Log.i(TAG, "move $from->$to with screen off, waking")
            ScreenWaker.wake(this, "alert slider")
            ignoreMovesUntil = now + POST_WAKE_GRACE_MS
            return
        }

        moveCount++
        Log.d(TAG, "move $from->$to with screen on, count=$moveCount")
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, GESTURE_SETTLE_MS)
    }

    private fun onGestureSettled() {
        val count = moveCount
        moveCount = 0
        when {
            count >= 3 -> {
                if (!Prefs.sliderPowerMenu(this)) return
                Log.i(TAG, "gesture: $count moves -> power menu")
                if (!ScreenActionService.powerDialog()) needAccessibility()
            }
            count == 2 -> {
                if (!Prefs.sliderLock(this)) return
                Log.i(TAG, "gesture: flick and return -> screen off")
                if (!ScreenActionService.lockScreen()) needAccessibility()
            }
            else -> Log.d(TAG, "gesture: single move, left to the system")
        }
    }

    private fun needAccessibility() {
        Toast.makeText(this, "Enable the AlertPower accessibility service to turn the screen off", Toast.LENGTH_LONG).show()
    }

    /** Locks the screen through the accessibility service, then wakes it 8 s later. */
    private fun runTestWake() {
        val pm = getSystemService(PowerManager::class.java)
        testWakeLock?.let { if (it.isHeld) it.release() }
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alertpower:test")
        wl.acquire(TEST_DELAY_MS + 5_000L)
        testWakeLock = wl

        if (!ScreenActionService.lockScreen()) {
            Toast.makeText(this, "Accessibility is off. Could not turn the screen off, waking in 8 s anyway", Toast.LENGTH_LONG).show()
        }
        handler.postDelayed({
            ScreenWaker.wake(this, "test")
            if (wl.isHeld) wl.release()
        }, TEST_DELAY_MS)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val test = PendingIntent.getService(
            this, 1, Intent(this, SliderWakeService::class.java).setAction(ACTION_TEST_WAKE), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SliderWakeService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_test), test)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
