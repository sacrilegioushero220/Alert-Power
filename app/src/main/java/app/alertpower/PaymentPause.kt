package app.alertpower

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * "Pay mode": keeps the accessibility service genuinely off while a payment app is open.
 *
 * Paytm (and Google Pay, BHIM, several bank apps) refuse to work while any accessibility
 * service from a sideloaded app is enabled. The slider wake never needed the service, so
 * while it is paused the slider still wakes the phone; screen off and the power menu come back
 * through [borrow] (turn on, act, turn off again).
 *
 * Who calls what:
 *  - [onTopApp]: the foreground watcher (every poll), the accessibility service (window events,
 *    package name only) and MainActivity (its own package on resume).
 *  - [pauseAndLaunch]: the "open Paytm through AlertPower" chips and shortcuts. The service is
 *    already off when the payment app starts, so even a launch-time check passes.
 *  - [toggle]: the quick settings tile and the notification action.
 *  - [borrow] / [onBorrowedActionDone]: SliderWakeService and ScreenActionService.
 *  - [onEnabledExternally]: ScreenActionService, when it comes up without AlertPower asking.
 *  - [ensureArmed]: anything that starts while a pause may be in progress (service, activity, tile).
 *
 * Everything runs on the main thread. State that must survive a process death lives in [Prefs]
 * ([Prefs.pausedByUs] is the one that matters: it marks the pause as ours to undo, so a service
 * the user turned off by hand is never turned on behind their back).
 */
object PaymentPause {
    private const val TAG = "PaymentPause"
    private const val SYSTEM_UI = "com.android.systemui"

    /** Time outside every payment app before the service is turned back on. */
    const val RESTORE_DELAY_MS = 4_000L
    /** After a manual pause, how long to wait for the payment app before the pause ends on its own. */
    const val MANUAL_GRACE_MS = 90_000L
    /** Safety net: a pause that nothing confirms as still needed ends after this. */
    const val PAUSE_MAX_MS = 15 * 60_000L
    private const val BORROW_LOCK_MS = 800L
    private const val BORROW_MENU_MS = 12_000L
    private const val BORROW_TIMEOUT_MS = 6_000L

    enum class Action { LOCK, POWER_DIALOG, RESTART }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var restoreRunnable: Runnable? = null
    private var safetyRunnable: Runnable? = null
    private var manualUntil = 0L
    private var paymentSeenSincePause = false
    private var borrowUntil = 0L

    /** Last foreground package reported, for status text. Null right after a restore so the next report is acted on. */
    @Volatile
    var currentTop: String? = null
        private set

    /** Set by SliderWakeService while its ForegroundWatcher is polling. */
    @Volatile
    var watcherActive = false

    fun isPaused(ctx: Context): Boolean = Prefs.pausedByUs(ctx)

    /** Without WRITE_SECURE_SETTINGS the service can be turned off but never back on. */
    fun canRestore(ctx: Context): Boolean = A11yToggle.canWrite(ctx)

    /** True while the service is on only for a borrowed action. */
    fun isBorrowing(): Boolean = SystemClock.elapsedRealtime() < borrowUntil

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    // ---------------------------------------------------------------- foreground

    fun onTopApp(ctx: Context, pkg: String) {
        if (pkg == SYSTEM_UI) return
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        val isPayment = pkg in Prefs.pausePackages(app)
        val paused = Prefs.pausedByUs(app)
        if (pkg == currentTop) {
            // Same app as last time. Act only if the state no longer fits it, e.g. a restore
            // (test wake, tile, safety net) happened while a payment app stayed in front.
            if (isPayment == paused) return
            if (!isPayment && restoreRunnable != null) return
        }
        currentTop = pkg
        if (isPayment) {
            paymentSeenSincePause = true
            cancelRestore()
            if (now < borrowUntil) return                       // a borrowed action is in flight
            // Auto-pause only when the service can be turned back on afterwards; otherwise the
            // user would be left turning it on in Settings, which is what pay mode exists to avoid.
            if ((Prefs.autoPause(app) && canRestore(app)) || paused) pause(app, pkg, manual = false)
        } else if (paused) {
            if (!paymentSeenSincePause && now < manualUntil) {
                // Manual pause, payment app not opened yet: let the grace period run out.
                scheduleRestore(app, manualUntil - now + RESTORE_DELAY_MS)
                return
            }
            // Never restore in the middle of a borrowed action; the borrowed instance would be
            // mistaken for a restored one.
            val afterBorrow = (borrowUntil - now).coerceAtLeast(0L)
            scheduleRestore(app, RESTORE_DELAY_MS + afterBorrow)
        }
    }

    // ---------------------------------------------------------------- pause / restore

    /** Turns the service off and remembers that AlertPower did it. Returns true when it is off afterwards. */
    fun pause(ctx: Context, reason: String, manual: Boolean): Boolean {
        val app = ctx.applicationContext
        cancelRestore()
        val wasOn = A11yToggle.isOn(app)
        if (wasOn && !A11yToggle.disable(app)) {
            Log.w(TAG, "pause for $reason: could not turn the service off")
            return false
        }
        if (!wasOn && !Prefs.pausedByUs(app)) {
            // Off already, and not by us: the user or the system did it. Leave it theirs.
            Log.i(TAG, "pause for $reason: service was already off")
            return true
        }
        val firstTime = !Prefs.pausedByUs(app)
        Prefs.setPaused(app, true, reason)
        if (firstTime) {
            paymentSeenSincePause = !manual
            manualUntil = if (manual) SystemClock.elapsedRealtime() + MANUAL_GRACE_MS else 0L
            // A manual pause with no payment app in sight ends on its own after the grace period.
            if (manual) scheduleRestore(app, MANUAL_GRACE_MS + RESTORE_DELAY_MS)
        }
        armSafetyNet(app, PAUSE_MAX_MS)
        Log.i(TAG, "paused for $reason (manual=$manual)")
        notifyStateChanged(app)
        return true
    }

    /** Turns the service back on if AlertPower was the one that paused it. */
    fun restore(ctx: Context): Boolean {
        val app = ctx.applicationContext
        cancelRestore()
        cancelSafetyNet()
        if (!Prefs.pausedByUs(app)) return true
        // Settings, not the running instance: right after disableSelf() the old instance lingers
        // until its unbind arrives, and right after enable() the new one is not bound yet.
        if (!A11yToggle.isEnabledInSettings(app) && !A11yToggle.enable(app)) {
            Log.w(TAG, "restore: WRITE_SECURE_SETTINGS not granted, turn the service on in Settings")
            return false
        }
        clearPause(app)
        Log.i(TAG, "restored")
        notifyStateChanged(app)
        return true
    }

    fun toggle(ctx: Context): Boolean =
        if (isPaused(ctx)) restore(ctx) else pause(ctx, "manual", manual = true)

    /** Pauses first, then opens [pkg], so even a launch-time check in the payment app sees no service. */
    fun pauseAndLaunch(ctx: Context, pkg: String): Boolean {
        val app = ctx.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        pause(app, pkg, manual = true)
        paymentSeenSincePause = true
        cancelRestore()
        currentTop = pkg
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        return true
    }

    /**
     * The service came up although AlertPower had paused it and is not borrowing it: the user
     * turned it on in Settings. The pause is over, and it is theirs again.
     */
    fun onEnabledExternally(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs.pausedByUs(app) || isBorrowing()) return
        cancelRestore()
        cancelSafetyNet()
        clearPause(app)
        Log.i(TAG, "service enabled outside pay mode, pause cleared")
        notifyStateChanged(app)
    }

    /**
     * Re-arms the safety net after a process restart: handler timers do not survive one, the
     * paused flag does. Call from anything that starts while a pause may be in progress.
     */
    fun ensureArmed(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs.pausedByUs(app) || safetyRunnable != null) return
        val elapsed = System.currentTimeMillis() - Prefs.pausedAt(app)
        val remaining = (PAUSE_MAX_MS - elapsed).coerceIn(RESTORE_DELAY_MS, PAUSE_MAX_MS)
        Log.i(TAG, "pause found on start, safety net in ${remaining / 1000} s")
        armSafetyNet(app, remaining)
    }

    private fun clearPause(app: Context) {
        Prefs.setPaused(app, false, null)
        paymentSeenSincePause = false
        manualUntil = 0L
        currentTop = null
    }

    // ---------------------------------------------------------------- borrow

    /**
     * Runs [action] while paused: turns the service on, lets the new instance perform the pending
     * action in onServiceConnected, then [onBorrowedActionDone] turns it off again.
     * Only LOCK and POWER_DIALOG; a restart reboots anyway.
     */
    fun borrow(ctx: Context, action: Action): Boolean {
        val app = ctx.applicationContext
        if (action == Action.RESTART) return false
        if (!Prefs.pausedByUs(app) || !A11yToggle.canWrite(app)) return false
        ScreenActionService.requestPending(action)
        borrowUntil = SystemClock.elapsedRealtime() + BORROW_TIMEOUT_MS
        val ok = A11yToggle.enable(app)
        if (!ok) {
            ScreenActionService.clearPending()
            borrowUntil = 0L
        }
        Log.i(TAG, "borrow $action -> $ok")
        return ok
    }

    /** Called by the service once a pending action has been performed (or dropped as stale). */
    fun onBorrowedActionDone(ctx: Context, action: Action) {
        val app = ctx.applicationContext
        if (!Prefs.pausedByUs(app)) {
            borrowUntil = 0L
            return
        }
        val delay = when (action) {
            Action.LOCK -> BORROW_LOCK_MS
            Action.POWER_DIALOG -> BORROW_MENU_MS
            Action.RESTART -> return
        }
        borrowUntil = SystemClock.elapsedRealtime() + delay + 1_000L
        handler.postDelayed({
            borrowUntil = 0L
            if (Prefs.pausedByUs(app)) {
                A11yToggle.disable(app)
                Log.i(TAG, "borrowed $action finished, paused again")
            }
        }, delay)
    }

    // ---------------------------------------------------------------- timers

    private fun scheduleRestore(ctx: Context, delayMs: Long) {
        cancelRestore()
        val r = Runnable {
            restoreRunnable = null
            restore(ctx)
        }
        restoreRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelRestore() {
        restoreRunnable?.let(handler::removeCallbacks)
        restoreRunnable = null
    }

    /**
     * Ends a pause that nothing confirms as still needed. Re-arms instead while a payment app is
     * known to be in front: confirmed by the watcher, or assumed while the screen is off (a
     * restore behind the keyguard would hand the user straight back into the payment app with
     * the service on).
     */
    private fun armSafetyNet(ctx: Context, delayMs: Long) {
        cancelSafetyNet()
        val r = object : Runnable {
            override fun run() {
                safetyRunnable = null
                if (!Prefs.pausedByUs(ctx)) return
                val payingTop = currentTop?.let { it in Prefs.pausePackages(ctx) } == true
                val stillPaying = payingTop && (watcherActive || !ScreenWaker.isScreenOn(ctx))
                if (stillPaying) {
                    safetyRunnable = this
                    handler.postDelayed(this, PAUSE_MAX_MS)
                } else {
                    Log.i(TAG, "safety net: pause expired")
                    restore(ctx)
                }
            }
        }
        safetyRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelSafetyNet() {
        safetyRunnable?.let(handler::removeCallbacks)
        safetyRunnable = null
    }

    /** Tells the UI, the notification and the tile that the pause or bind state changed. */
    fun notifyStateChanged(ctx: Context) {
        for (l in listeners) l()
        PayModeTile.refresh(ctx.applicationContext)
    }
}
