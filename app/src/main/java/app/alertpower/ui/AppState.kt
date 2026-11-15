package app.alertpower.ui

import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A launchable app as the UI shows it: package, label and launcher icon. */
data class PaymentApp(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
)

/** Observable snapshot of everything the UI shows. Refreshed by the activity on resume. */
class AppState {
    var notificationsGranted by mutableStateOf(false)
    /** True while the accessibility service runs OR while pay mode has paused it (a pause is not a setup problem). */
    var accessibilityOn by mutableStateOf(false)
    var batteryUnrestricted by mutableStateOf(false)
    var overlayGranted by mutableStateOf(false)

    var serviceRunning by mutableStateOf(false)
    var wakeOnSlider by mutableStateOf(true)
    var lockOnFlick by mutableStateOf(true)
    var powerMenuOnTriple by mutableStateOf(true)
    var systemButton by mutableStateOf(true)
    var floatingButton by mutableStateOf(false)

    // ---- pay mode ----

    /** WRITE_SECURE_SETTINGS granted over adb, so the service can be turned back on. */
    var canToggleA11y by mutableStateOf(false)
    /** Usage access granted, so the foreground watcher can see when a payment app is left. */
    var usageAccessGranted by mutableStateOf(false)
    var autoPause by mutableStateOf(true)
    /** AlertPower turned its own accessibility service off for a payment. */
    var a11yPaused by mutableStateOf(false)
    /** Label of the app the pause was for; null for a pause started by hand. */
    var pausedFor by mutableStateOf<String?>(null)
    /** OxygenOS "permission monitoring" still on (adb grants blocked). null = could not tell. */
    var permissionMonitoringOn by mutableStateOf<Boolean?>(null)
    /** Selected pause packages that are installed, with label and icon. */
    var paymentApps by mutableStateOf<List<PaymentApp>>(emptyList())
    /** Every launchable app, loaded lazily by [AppActions.loadAllApps]. */
    var allApps by mutableStateOf<List<PaymentApp>>(emptyList())
    var pausePackages by mutableStateOf<Set<String>>(emptySet())

    val requiredStepsLeft: Int
        get() = listOf(notificationsGranted, accessibilityOn, batteryUnrestricted).count { !it }

    val setupComplete: Boolean get() = requiredStepsLeft == 0
}

/** Everything the UI can ask the platform to do. Implemented by MainActivity. */
interface AppActions {
    fun setServiceEnabled(enabled: Boolean)
    fun setWakeOnSlider(enabled: Boolean)
    fun setLockOnFlick(enabled: Boolean)
    fun setPowerMenuOnTriple(enabled: Boolean)
    fun setSystemButton(enabled: Boolean)
    fun setFloatingButton(enabled: Boolean)

    fun requestNotifications()
    fun openAccessibilitySettings()
    fun requestBatteryUnrestricted()
    fun requestOverlay()
    fun openAppSettings()

    /** Returns an error message to show, or null when the action was started. */
    fun testWake(): String?

    /** Returns an error message to show, or null when the restart sequence was started. */
    fun restartPhone(): String?

    // ---- pay mode ----

    fun setAutoPause(enabled: Boolean)

    /** Turns the accessibility service off now and marks the pause as AlertPower's to undo. */
    fun pauseForPayment()

    /** Turns the accessibility service back on after a pause. */
    fun resumeFromPause()

    /** Pauses first, then opens [pkg]. Returns an error message, or null when the app was started. */
    fun launchPaymentApp(pkg: String): String?

    /** Asks the launcher to pin a "pause, then open [pkg]" shortcut. Returns an error message, or null. */
    fun pinPaymentShortcut(pkg: String): String?

    fun setPausePackages(packages: Set<String>)

    /** Fills [AppState.allApps] off the main thread. Safe to call more than once. */
    fun loadAllApps()

    fun openUsageAccessSettings()

    /** Offers to add the Pay mode quick settings tile (Android 13+), or explains how to add it by hand. */
    fun requestQsTile()

    fun copyToClipboard(label: String, text: String)

    /** Turns the accessibility service on through settings. Only offered when [AppState.canToggleA11y]. */
    fun turnAccessibilityOn(): Boolean
}
