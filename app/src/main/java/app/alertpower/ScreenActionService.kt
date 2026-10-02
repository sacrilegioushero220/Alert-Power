package app.alertpower

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

/**
 * Accessibility service used as an action performer:
 *
 *  - lockScreen()   : GLOBAL_ACTION_LOCK_SCREEN, same as a short power press
 *  - powerDialog()  : GLOBAL_ACTION_POWER_DIALOG, same as a long power press
 *  - restartPhone() : opens the power dialog, then for a few seconds either
 *                       a) performs a two-finger swipe UP when the menu says
 *                          "Swipe up with two fingers to restart" (OxygenOS 13/14), or
 *                       b) taps any button labelled "Restart"/"Reboot" (stock-style menus),
 *                     including a confirmation step if one appears.
 *                     It never swipes down and never taps anything containing "power off",
 *                     "shut down", "emergency", "lockdown" or "cancel".
 *
 * It also owns the two on-screen buttons:
 *  - Android's accessibility button (floating or in the navigation bar): the system only
 *    reports taps, so tap = screen off and double tap = power menu.
 *  - AlertPower's own floating button ([FloatingButton]): tap = screen off, hold = power menu.
 *
 * Window content is only inspected while a restart requested by the user is in progress.
 *
 * GLOBAL_ACTION_LOCK_SCREEN is used instead of DevicePolicyManager.lockNow() on purpose:
 * lockNow() forces PIN entry and disables biometrics on the next unlock.
 */
class ScreenActionService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenActionService"
        private const val RESTART_HUNT_MS = 8_000L
        private const val HUNT_INTERVAL_MS = 250L
        private const val MAX_CLIMB = 6
        private const val SWIPE_DURATION_MS = 320L
        private const val MAX_SWIPES = 2
        /** If nothing matched by then but the power menu is up, swipe anyway. */
        private const val BLIND_SWIPE_AFTER_MS = 1_800L
        /** Second tap on the system accessibility button within this = power menu. */
        private const val DOUBLE_TAP_MS = 400L

        private val RESTART_WORDS = listOf("restart", "reboot")
        private val FORBIDDEN_WORDS = listOf(
            "power off", "poweroff", "shut", "switch off", "turn off", "emergency", "lockdown", "cancel"
        )

        @Volatile
        private var instance: ScreenActionService? = null

        val isRunning: Boolean get() = instance != null

        fun lockScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        fun powerDialog(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) ?: false

        fun restartPhone(): Boolean = instance?.beginRestart() ?: false
    }

    private val handler = Handler(Looper.getMainLooper())

    // ---- restart hunt ----
    private var huntUntil = 0L
    private var huntStarted = 0L
    private var restartClicks = 0
    private var swipes = 0
    private var loggedTree = false

    // ---- system accessibility button ----
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    private var pendingButtonTap: Runnable? = null
    private var lastButtonTap = 0L

    // ---- own floating button ----
    private var floatingButton: FloatingButton? = null
    private var screenReceiverRegistered = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_FLOATING_BUTTON) syncFloatingButton()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> floatingButton?.hide()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> syncFloatingButton()
            }
        }
    }

    private val huntRunnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() > huntUntil) {
                Log.w(TAG, "restart hunt finished, $restartClicks click(s), $swipes swipe(s)")
                return
            }
            if (clickRestartButton()) restartClicks++
            handler.postDelayed(this, HUNT_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerSystemButton()
        floatingButton = FloatingButton(
            service = this,
            onTap = {
                Log.i(TAG, "floating button tap -> screen off")
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            },
            onHold = {
                Log.i(TAG, "floating button hold -> power menu")
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            },
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        screenReceiverRegistered = true
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        syncFloatingButton()
        Log.i(TAG, "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun tearDown() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        floatingButton?.hide()
        floatingButton = null
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            screenReceiverRegistered = false
        }
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        buttonCallback?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        buttonCallback = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || SystemClock.uptimeMillis() > huntUntil) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            // A new window appeared during the hunt: look right away instead of waiting for the tick.
            handler.removeCallbacks(huntRunnable)
            handler.postDelayed(huntRunnable, 120)
        }
    }

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- buttons

    private fun registerSystemButton() {
        val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) = onSystemButtonTapped()
            override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                Log.i(TAG, "system accessibility button available=$available")
            }
        }
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(cb)
            buttonCallback = cb
        } catch (e: Exception) {
            Log.e(TAG, "could not register accessibility button callback", e)
        }
    }

    /** Android only reports taps on its button, so: tap = screen off, double tap = power menu. */
    private fun onSystemButtonTapped() {
        if (!Prefs.systemButton(this)) {
            Log.i(TAG, "system button tap ignored, disabled in AlertPower")
            return
        }
        val now = SystemClock.uptimeMillis()
        val pending = pendingButtonTap
        if (pending != null && now - lastButtonTap <= DOUBLE_TAP_MS) {
            handler.removeCallbacks(pending)
            pendingButtonTap = null
            Log.i(TAG, "system button double tap -> power menu")
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            return
        }
        lastButtonTap = now
        val r = Runnable {
            pendingButtonTap = null
            Log.i(TAG, "system button tap -> screen off")
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        pendingButtonTap = r
        handler.postDelayed(r, DOUBLE_TAP_MS)
    }

    /** Shows the overlay button only when enabled, the screen is on and the phone is unlocked. */
    private fun syncFloatingButton() {
        val fab = floatingButton ?: return
        val keyguard = getSystemService(KeyguardManager::class.java)
        val want = Prefs.floatingButton(this) && ScreenWaker.isScreenOn(this) && !keyguard.isKeyguardLocked
        if (want) fab.show() else fab.hide()
    }

    // ---------------------------------------------------------------- restart

    private fun beginRestart(): Boolean {
        if (!performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)) return false
        Log.i(TAG, "restart requested, hunting for the Restart control")
        restartClicks = 0
        swipes = 0
        loggedTree = false
        huntStarted = SystemClock.uptimeMillis()
        huntUntil = huntStarted + RESTART_HUNT_MS
        handler.removeCallbacks(huntRunnable)
        handler.postDelayed(huntRunnable, 450)
        return true
    }

    private fun clickRestartButton(): Boolean {
        val active = rootInActiveWindow
        if (active != null) {
            if (!loggedTree) { logTexts(active); loggedTree = true }
            if (clickRestartIn(active)) return true
            if (swipes < MAX_SWIPES && mentionsTwoFingerRestart(active)) {
                twoFingerSwipeUp("menu asks for a two-finger swipe")
                return true
            }
        }
        for (window in windows) {
            val root = window.root ?: continue
            if (clickRestartIn(root)) return true
        }
        // OxygenOS fallback: the menu is up (system UI is active) but no label matched, e.g. another language.
        val elapsed = SystemClock.uptimeMillis() - huntStarted
        if (swipes == 0 && elapsed >= BLIND_SWIPE_AFTER_MS &&
            active?.packageName?.toString() == "com.android.systemui"
        ) {
            twoFingerSwipeUp("no label matched, system UI is in front")
            return true
        }
        return false
    }

    /** True when the window carries OxygenOS's "Swipe up with two fingers to restart" hint. */
    private fun mentionsTwoFingerRestart(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText("two fingers") ?: return false
        return nodes.any { labelOf(it).let { l -> l.contains("swipe up") && l.contains("restart") } }
    }

    /**
     * Two parallel strokes moving UP across the middle of the screen. Up = restart on OxygenOS.
     * Down would be power off, which this app must never do.
     */
    private fun twoFingerSwipeUp(reason: String) {
        swipes++
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val startY = h * 0.68f
        val endY = h * 0.30f
        val left = Path().apply { moveTo(w * 0.42f, startY); lineTo(w * 0.42f, endY) }
        val right = Path().apply { moveTo(w * 0.58f, startY); lineTo(w * 0.58f, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0, SWIPE_DURATION_MS))
            .addStroke(GestureDescription.StrokeDescription(right, 0, SWIPE_DURATION_MS))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { Log.i(TAG, "two-finger swipe up completed") }
            override fun onCancelled(g: GestureDescription?) { Log.w(TAG, "two-finger swipe up cancelled") }
        }, null)
        Log.i(TAG, "two-finger swipe up #$swipes dispatched=$ok ($reason), screen ${w.toInt()}x${h.toInt()}")
    }

    private fun clickRestartIn(root: AccessibilityNodeInfo): Boolean {
        for (word in RESTART_WORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(word) ?: continue
            for (node in nodes) {
                val label = labelOf(node)
                if (!label.contains(word)) continue
                if (FORBIDDEN_WORDS.any { label.contains(it) }) continue

                var target: AccessibilityNodeInfo? = node
                var climbed = 0
                while (target != null && !target.isClickable && climbed < MAX_CLIMB) {
                    target = target.parent
                    climbed++
                }
                if (target != null && target.isClickable &&
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    Log.i(TAG, "tapped '$label' in ${root.packageName}")
                    return true
                }
            }
        }
        return false
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        ((node.text ?: "").toString() + " " + (node.contentDescription ?: "").toString())
            .trim().lowercase()

    /** One-time dump of what the power menu offers, so a mismatch is easy to diagnose in logcat. */
    private fun logTexts(root: AccessibilityNodeInfo) {
        val texts = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 12 || texts.size > 30) return
            val label = labelOf(n)
            if (label.isNotEmpty()) texts.add(label)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        Log.i(TAG, "active window ${root.packageName} texts: $texts")
    }
}
