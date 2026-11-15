package app.alertpower

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Turns AlertPower's own accessibility service off and on.
 *
 * Off needs no permission: a running service can call [android.accessibilityservice.AccessibilityService.disableSelf],
 * which synchronously removes it from ENABLED_ACCESSIBILITY_SERVICES and unbinds it.
 *
 * On needs android.permission.WRITE_SECURE_SETTINGS, which only adb can grant:
 *   adb shell pm grant app.alertpower android.permission.WRITE_SECURE_SETTINGS
 * (On OxygenOS, "Disable permission monitoring" in Developer options must be on first.)
 * The grant survives reboots and reinstalls of the same signature; it is lost on uninstall.
 *
 * Android stores the setting as colon-separated flattened ComponentNames in either the short
 * ("pkg/.Cls") or long ("pkg/pkg.Cls") form, so entries are compared through
 * [ComponentName.unflattenFromString] rather than as strings, and other services are preserved.
 */
object A11yToggle {
    private const val TAG = "A11yToggle"
    private const val SEPARATOR = ":"

    fun component(ctx: Context): ComponentName = ComponentName(ctx, ScreenActionService::class.java)

    fun canWrite(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun enabledServices(ctx: Context): List<ComponentName> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }

    /** True when Android lists the service as enabled, whether or not it has been bound yet. */
    fun isEnabledInSettings(ctx: Context): Boolean = enabledServices(ctx).contains(component(ctx))

    /** True when the service is enabled in settings or currently running. */
    fun isOn(ctx: Context): Boolean = ScreenActionService.isRunning || isEnabledInSettings(ctx)

    /** Adds the service to the enabled list. Android binds it asynchronously and creates a new instance. */
    fun enable(ctx: Context): Boolean {
        if (!canWrite(ctx)) {
            Log.w(TAG, "enable: WRITE_SECURE_SETTINGS not granted")
            return false
        }
        return try {
            val me = component(ctx)
            val current = enabledServices(ctx)
            val next = if (current.contains(me)) current else current + me
            val cr = ctx.contentResolver
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, flatten(next))
            Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            Log.i(TAG, "enabled in settings")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "enable failed", e)
            false
        }
    }

    /** Prefers disableSelf() on the running instance, falls back to a settings write. */
    fun disable(ctx: Context): Boolean {
        if (ScreenActionService.disableSelfNow()) {
            Log.i(TAG, "disabled through disableSelf()")
            return true
        }
        if (!isEnabledInSettings(ctx)) return true
        if (!canWrite(ctx)) {
            Log.w(TAG, "disable: not running and WRITE_SECURE_SETTINGS not granted")
            return false
        }
        return try {
            val me = component(ctx)
            val next = enabledServices(ctx).filter { it != me }
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, flatten(next))
            Log.i(TAG, "disabled in settings")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "disable failed", e)
            false
        }
    }

    private fun flatten(list: List<ComponentName>): String =
        list.joinToString(SEPARATOR) { it.flattenToString() }
}
