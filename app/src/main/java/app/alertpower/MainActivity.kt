package app.alertpower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.alertpower.ui.AlertPowerApp
import app.alertpower.ui.AlertPowerTheme
import app.alertpower.ui.AppActions
import app.alertpower.ui.AppState

class MainActivity : ComponentActivity(), AppActions {

    private val state = AppState()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AlertPowerTheme {
                AlertPowerApp(state = state, actions = this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        state.notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        state.accessibilityOn = ScreenActionService.isRunning
        state.batteryUnrestricted =
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        state.overlayGranted = Settings.canDrawOverlays(this)
        state.serviceRunning = SliderWakeService.running
        state.wakeOnSlider = Prefs.wakeOnSlider(this)
        state.lockOnFlick = Prefs.sliderLock(this)
        state.powerMenuOnTriple = Prefs.sliderPowerMenu(this)
        state.systemButton = Prefs.systemButton(this)
        state.floatingButton = Prefs.floatingButton(this)
    }

    // ---- AppActions ----

    override fun setServiceEnabled(enabled: Boolean) {
        if (enabled) SliderWakeService.start(this) else SliderWakeService.stop(this)
        state.serviceRunning = enabled
        window.decorView.postDelayed({ refresh() }, 600)
    }

    override fun setWakeOnSlider(enabled: Boolean) {
        Prefs.setBoolean(this, Prefs.KEY_WAKE_ON_SLIDER, enabled)
        state.wakeOnSlider = enabled
    }

    override fun setLockOnFlick(enabled: Boolean) {
        Prefs.setBoolean(this, Prefs.KEY_SLIDER_LOCK, enabled)
        state.lockOnFlick = enabled
    }

    override fun setPowerMenuOnTriple(enabled: Boolean) {
        Prefs.setBoolean(this, Prefs.KEY_SLIDER_POWER_MENU, enabled)
        state.powerMenuOnTriple = enabled
    }

    override fun setSystemButton(enabled: Boolean) {
        Prefs.setBoolean(this, Prefs.KEY_SYSTEM_BUTTON, enabled)
        state.systemButton = enabled
    }

    override fun setFloatingButton(enabled: Boolean) {
        Prefs.setBoolean(this, Prefs.KEY_FLOATING_BUTTON, enabled)
        state.floatingButton = enabled
    }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun requestBatteryUnrestricted() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    override fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun testWake(): String? {
        if (!SliderWakeService.running) return "Turn AlertPower on first"
        if (!ScreenActionService.isRunning) return "Enable the accessibility service first"
        window.decorView.postDelayed({ SliderWakeService.testWake(this) }, 1_000)
        return null
    }

    override fun restartPhone(): String? {
        if (!ScreenActionService.isRunning) return "Enable the accessibility service first"
        return if (ScreenActionService.restartPhone()) null else "Could not open the power menu"
    }

}
