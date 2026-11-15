package app.alertpower

import android.Manifest
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import app.alertpower.ui.AlertPowerApp
import app.alertpower.ui.AlertPowerTheme
import app.alertpower.ui.AppActions
import app.alertpower.ui.AppState
import app.alertpower.ui.PaymentApp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), AppActions {

    companion object {
        private const val TAG = "MainActivity"
        /** Shortcut intent contract shared with PayLaunchActivity. */
        private const val PAY_LAUNCH_ACTION = "app.alertpower.action.PAY_LAUNCH"
        private const val EXTRA_PACKAGE = "package"
        private const val PERMISSION_MONITORING_PROP = "persist.sys.permission.enable"
        private const val MAX_DYNAMIC_SHORTCUTS = 4
        private const val SHORTCUT_ICON_DP = 108
        private const val PAUSE_REASON_MANUAL = "manual"
    }

    private val state = AppState()

    /** Label and icon per package, so refresh() does not reload drawables every time. */
    private val appCache = HashMap<String, PaymentApp>()
    /** Packages whose dynamic shortcuts are currently published, to skip redundant republishes. */
    private var publishedShortcuts: List<String>? = null
    @Volatile
    private var loadingApps = false

    /** Pause state changed: refresh now, and again once the restored service has had time to bind. */
    private val pauseListener: () -> Unit = {
        refresh()
        window.decorView.postDelayed({ refresh() }, 900)
    }

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

    /** Read once per resume: the fallback forks getprop, which must not happen on every refresh. */
    private var permissionMonitoringOn: Boolean? = null

    override fun onResume() {
        super.onResume()
        permissionMonitoringOn = readPermissionMonitoring()
        PaymentPause.addListener(pauseListener)
        PaymentPause.ensureArmed(this)
        // A grant may have arrived while the slider service was running: let it re-check the watcher.
        SliderWakeService.sync(this)
        // We are in front: a service paused for a payment app may be restored now.
        PaymentPause.onTopApp(this, packageName)
        refresh()
    }

    override fun onPause() {
        PaymentPause.removeListener(pauseListener)
        super.onPause()
    }

    private fun refresh() {
        state.notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        state.batteryUnrestricted =
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        state.overlayGranted = Settings.canDrawOverlays(this)
        state.serviceRunning = SliderWakeService.running
        state.wakeOnSlider = Prefs.wakeOnSlider(this)
        state.lockOnFlick = Prefs.sliderLock(this)
        state.powerMenuOnTriple = Prefs.sliderPowerMenu(this)
        state.systemButton = Prefs.systemButton(this)
        state.floatingButton = Prefs.floatingButton(this)

        // ---- pay mode ----
        state.canToggleA11y = A11yToggle.canWrite(this)
        state.usageAccessGranted = ForegroundWatcher.hasUsageAccess(this)
        state.a11yPaused = PaymentPause.isPaused(this)
        state.pausedFor = Prefs.pausedFor(this)
            ?.takeIf { it != PAUSE_REASON_MANUAL }
            ?.let { appInfo(it)?.label ?: it }
        state.autoPause = Prefs.autoPause(this)
        state.permissionMonitoringOn = permissionMonitoringOn
        // "On" also while the setting is written but the service has not bound yet (right after a restore),
        // otherwise the hero card flashes "Setup needed" for the binding window.
        state.accessibilityOn = ScreenActionService.isRunning || state.a11yPaused || A11yToggle.isEnabledInSettings(this)
        val packages = Prefs.pausePackages(this)
        state.pausePackages = packages
        state.paymentApps = packages.mapNotNull(::appInfo).sortedBy { it.label.lowercase() }
        publishDynamicShortcuts(state.paymentApps)
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
        if (!ScreenActionService.isRunning && !PaymentPause.isPaused(this)) return "Enable the accessibility service first"
        window.decorView.postDelayed({ SliderWakeService.testWake(this) }, 1_000)
        return null
    }

    override fun restartPhone(): String? {
        if (!ScreenActionService.isRunning) return "Enable the accessibility service first"
        return if (ScreenActionService.restartPhone()) null else "Could not open the power menu"
    }

    // ---- AppActions: pay mode ----

    override fun setAutoPause(enabled: Boolean) {
        Prefs.setBoolean(this, Prefs.KEY_AUTO_PAUSE, enabled)
        state.autoPause = enabled
    }

    override fun pauseForPayment() {
        PaymentPause.pause(this, PAUSE_REASON_MANUAL, manual = true)
        refresh()
    }

    override fun resumeFromPause() {
        if (!PaymentPause.restore(this)) {
            toast("Could not turn the service back on. Turn AlertPower on in Accessibility settings.")
            openAccessibilitySettings()
        }
        // The new service instance binds asynchronously; show it once it is there.
        window.decorView.postDelayed({ refresh() }, 700)
        refresh()
    }

    override fun launchPaymentApp(pkg: String): String? {
        val label = appInfo(pkg)?.label ?: return "That app is not installed"
        val ok = try {
            PaymentPause.pauseAndLaunch(this, pkg)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "launch $pkg", e)
            false
        }
        return if (ok) null else "Could not open $label"
    }

    override fun pinPaymentShortcut(pkg: String): String? {
        val app = appInfo(pkg) ?: return "That app is not installed"
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            return "This launcher does not support pinned shortcuts"
        }
        return try {
            if (ShortcutManagerCompat.requestPinShortcut(this, shortcutFor(app), null)) null
            else "Could not add the shortcut"
        } catch (e: Exception) {
            Log.w(TAG, "pin shortcut for $pkg", e)
            "Could not add the shortcut"
        }
    }

    override fun setPausePackages(packages: Set<String>) {
        Prefs.setPausePackages(this, packages)
        state.pausePackages = packages
        refresh()
    }

    override fun loadAllApps() {
        if (loadingApps) return
        loadingApps = true
        val pm = packageManager
        val self = packageName
        Thread({
            val list = try {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(launcher, 0)
                }
                resolved.asSequence()
                    .map { it.activityInfo.applicationInfo }
                    .filter { it.packageName != self }
                    .distinctBy { it.packageName }
                    .map { PaymentApp(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            } catch (e: Exception) {
                Log.e(TAG, "loadAllApps failed", e)
                emptyList()
            }
            runOnUiThread {
                for (app in list) appCache[app.pkg] = app
                state.allApps = list
                loadingApps = false
            }
        }, "alertpower-apps").start()
    }

    override fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun requestQsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, PayModeTile::class.java),
                "Pay mode",
                Icon.createWithResource(this, R.drawable.ic_notification),
                mainExecutor,
            ) { result ->
                when {
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        toast("The Pay mode tile is already in quick settings")
                    result >= StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE ->
                        toast("Could not ask the system. Open quick settings, tap the pencil and drag in the AlertPower pay mode tile")
                }
            }
        } else {
            toast("Open quick settings, tap the pencil and drag in the AlertPower pay mode tile")
        }
    }

    override fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own "Copied" overlay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Copied")
    }

    override fun turnAccessibilityOn(): Boolean {
        val ok = A11yToggle.enable(this)
        if (ok) window.decorView.postDelayed({ refresh() }, 700)
        return ok
    }

    // ---- helpers ----

    /** Label and icon of an installed package, cached; null when it is not installed (or not visible to us). */
    private fun appInfo(pkg: String): PaymentApp? = try {
        val pm = packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        appCache.getOrPut(pkg) { PaymentApp(pkg, info.loadLabel(pm).toString(), info.loadIcon(pm)) }
    } catch (_: PackageManager.NameNotFoundException) {
        appCache.remove(pkg)
        null
    }

    /** A "pause, then open" shortcut for [app], targeting PayLaunchActivity. */
    private fun shortcutFor(app: PaymentApp): ShortcutInfoCompat {
        val intent = Intent(this, PayLaunchActivity::class.java)
            .setAction(PAY_LAUNCH_ACTION)
            .putExtra(EXTRA_PACKAGE, app.pkg)
        val px = (SHORTCUT_ICON_DP * resources.displayMetrics.density).roundToInt()
        val icon = app.icon?.let { drawable ->
            try {
                if (drawable is AdaptiveIconDrawable) {
                    // Hand the launcher the unmasked 108 dp layers so it applies its own mask once,
                    // instead of re-wrapping an already masked bitmap.
                    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.background?.apply { setBounds(0, 0, px, px); draw(canvas) }
                    drawable.foreground?.apply { setBounds(0, 0, px, px); draw(canvas) }
                    IconCompat.createWithAdaptiveBitmap(bitmap)
                } else {
                    IconCompat.createWithBitmap(drawable.toBitmap(px, px))
                }
            } catch (e: Exception) {
                Log.w(TAG, "icon for ${app.pkg}", e)
                null
            }
        } ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        return ShortcutInfoCompat.Builder(this, "pay_${app.pkg}")
            .setShortLabel(app.label)
            .setLongLabel("Pay with ${app.label}")
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    /** Long-press launcher shortcuts for the first few selected payment apps. Best effort. */
    private fun publishDynamicShortcuts(apps: List<PaymentApp>) {
        val key = apps.map { it.pkg }.take(MAX_DYNAMIC_SHORTCUTS)
        if (key == publishedShortcuts) return
        try {
            val max = minOf(MAX_DYNAMIC_SHORTCUTS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(this))
            val shortcuts = apps.take(max.coerceAtLeast(0)).map(::shortcutFor)
            ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts)
            publishedShortcuts = key
        } catch (e: Exception) {
            Log.w(TAG, "dynamic shortcuts", e)
        }
    }

    /** "true" -> true, "false" -> false, anything else or unreadable -> null. */
    private fun readPermissionMonitoring(): Boolean? =
        when (systemProperty(PERMISSION_MONITORING_PROP)?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun systemProperty(key: String): String? {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            val value = get.invoke(null, key, "") as? String
            if (!value.isNullOrEmpty()) return value
        } catch (e: Exception) {
            Log.d(TAG, "SystemProperties.get unavailable: $e")
        }
        return try {
            val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            out.ifEmpty { null }
        } catch (e: Exception) {
            Log.d(TAG, "getprop unavailable: $e")
            null
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
