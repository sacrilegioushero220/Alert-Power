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
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
 *
 * Pay mode ([PaymentPause]): this service also owns the [ForegroundWatcher] that notices, without
 * accessibility, which app is in front, so the accessibility service can be paused for payment
 * apps and restored afterwards. It polls only while the screen is on, auto-pause is enabled and
 * there is at least one app to watch for. While the accessibility service is paused, the
 * screen-off and power-menu gestures borrow it for one action; the slider wake never needed it.
 */
class SliderWakeService : Service() {

    companion object {
        private const val TAG = "SliderWakeService"
        const val CHANNEL_ID = "alertpower_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "app.alertpower.action.START"
        const val ACTION_STOP = "app.alertpower.action.STOP"
        const val ACTION_TEST_WAKE = "app.alertpower.action.TEST_WAKE"
        const val ACTION_PAY_MODE = "app.alertpower.action.PAY_MODE"
        /** Re-evaluate the foreground watcher, e.g. after a permission was granted while running. */
        const val ACTION_SYNC = "app.alertpower.action.SYNC"
        /** How long to wait for a restored service to bind before giving up on a test wake. */
        private const val REBIND_POLL_MS = 100L
        private const val REBIND_MAX_POLLS = 30

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

        /** Asks a running service to re-check whether the foreground watcher should run. No-op when stopped. */
        fun sync(ctx: Context) {
            if (!running) return
            try {
                ctx.startService(Intent(ctx, SliderWakeService::class.java).setAction(ACTION_SYNC))
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
            }
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

    // ---- pay mode ----
    private lateinit var watcher: ForegroundWatcher
    private val pauseListener: () -> Unit = { onPauseStateChanged() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_AUTO_PAUSE || key == Prefs.KEY_PAUSE_PACKAGES) syncWatcher()
    }

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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopWatcher()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> syncWatcher()
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

        // Pay mode: watch the foreground app while the screen is on, follow pause changes.
        watcher = ForegroundWatcher(this) { pkg -> PaymentPause.onTopApp(this, pkg) }
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, screenFilter, ContextCompat.RECEIVER_EXPORTED)
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        PaymentPause.addListener(pauseListener)
        PaymentPause.ensureArmed(this)
        syncWatcher()

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
            ACTION_PAY_MODE -> togglePayMode()
            ACTION_SYNC -> {
                syncWatcher()
                PaymentPause.ensureArmed(this)
            }
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
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        PaymentPause.removeListener(pauseListener)
        stopWatcher()
        testWakeLock?.let { if (it.isHeld) it.release() }
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pay mode

    /**
     * Polls the foreground app only while the screen is on, auto-pause has something to watch for and
     * the service could be restored afterwards (WRITE_SECURE_SETTINGS granted).
     */
    private fun syncWatcher() {
        val want = ScreenWaker.isScreenOn(this) && Prefs.autoPause(this) &&
            Prefs.pausePackages(this).isNotEmpty() && A11yToggle.canWrite(this)
        if (want) watcher.start() else watcher.stop()
        PaymentPause.watcherActive = watcher.running
    }

    private fun stopWatcher() {
        if (::watcher.isInitialized) watcher.stop()
        PaymentPause.watcherActive = false
    }

    /** Notification action "Pay mode": pause now, or resume if paused. */
    private fun togglePayMode() {
        val ok = PaymentPause.toggle(this)
        Log.i(TAG, "pay mode toggled -> ok=$ok paused=${PaymentPause.isPaused(this)}")
        if (!ok) {
            Toast.makeText(this, "Could not turn the service back on. Finish the adb setup in AlertPower.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPauseStateChanged() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    /** Pay mode: the accessibility service is off on purpose, turn it on for this one action. */
    private fun borrowOrToast(action: PaymentPause.Action) {
        if (PaymentPause.borrow(this, action)) return
        Toast.makeText(this, "AlertPower is paused for payments", Toast.LENGTH_SHORT).show()
    }

    /** App label for the notification while paused; falls back to the stored value. */
    private fun pausedLabel(): String {
        val raw = Prefs.pausedFor(this) ?: return "payments"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(raw, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            if (raw == "manual") "payments" else raw
        }
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
        // Pay mode: the accessibility service is off because AlertPower paused it; borrow it.
        val paused = !ScreenActionService.isRunning && PaymentPause.isPaused(this)
        when {
            count >= 3 -> {
                if (!Prefs.sliderPowerMenu(this)) return
                Log.i(TAG, "gesture: $count moves -> power menu${if (paused) " (paused, borrowing)" else ""}")
                if (paused) borrowOrToast(PaymentPause.Action.POWER_DIALOG)
                else if (!ScreenActionService.powerDialog()) needAccessibility()
            }
            count == 2 -> {
                if (!Prefs.sliderLock(this)) return
                Log.i(TAG, "gesture: flick and return -> screen off${if (paused) " (paused, borrowing)" else ""}")
                if (paused) borrowOrToast(PaymentPause.Action.LOCK)
                else if (!ScreenActionService.lockScreen()) needAccessibility()
            }
            else -> Log.d(TAG, "gesture: single move, left to the system")
        }
    }

    private fun needAccessibility() {
        Toast.makeText(this, "Enable the AlertPower accessibility service to turn the screen off", Toast.LENGTH_LONG).show()
    }

    /** Locks the screen through the accessibility service, then wakes it 8 s later. */
    private fun runTestWake() {
        if (PaymentPause.isPaused(this) && PaymentPause.restore(this)) {
            // Pay mode: the service was paused; it is enabled again now, wait for it to bind.
            Log.i(TAG, "test wake: service was paused, restored it first")
            waitForServiceThenTest(0)
            return
        }
        doTestWake()
    }

    private fun waitForServiceThenTest(polls: Int) {
        if (ScreenActionService.isRunning || polls >= REBIND_MAX_POLLS) {
            doTestWake()
        } else {
            handler.postDelayed({ waitForServiceThenTest(polls + 1) }, REBIND_POLL_MS)
        }
    }

    private fun doTestWake() {
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
        val pay = PendingIntent.getService(
            this, 3, Intent(this, SliderWakeService::class.java).setAction(ACTION_PAY_MODE), flags
        )
        val text = if (PaymentPause.isPaused(this)) {
            getString(R.string.notif_text_paused, pausedLabel())
        } else {
            getString(R.string.notif_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_pay), pay)
            .addAction(0, getString(R.string.notif_action_test), test)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
