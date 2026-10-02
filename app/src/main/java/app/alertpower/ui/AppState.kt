package app.alertpower.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Observable snapshot of everything the UI shows. Refreshed by the activity on resume. */
class AppState {
    var notificationsGranted by mutableStateOf(false)
    var accessibilityOn by mutableStateOf(false)
    var batteryUnrestricted by mutableStateOf(false)
    var overlayGranted by mutableStateOf(false)

    var serviceRunning by mutableStateOf(false)
    var wakeOnSlider by mutableStateOf(true)
    var lockOnFlick by mutableStateOf(true)
    var powerMenuOnTriple by mutableStateOf(true)
    var systemButton by mutableStateOf(true)
    var floatingButton by mutableStateOf(false)

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
}
