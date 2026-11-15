package app.alertpower

import android.content.Context
import android.content.SharedPreferences

/** Tiny SharedPreferences wrapper. Everything the services read lives here. */
object Prefs {
    private const val FILE = "alertpower"

    const val KEY_WAKE_ON_SLIDER = "wake_on_slider"
    const val KEY_SLIDER_LOCK = "slider_lock"
    const val KEY_SLIDER_POWER_MENU = "slider_power_menu"
    const val KEY_SERVICE_ENABLED = "service_enabled"
    const val KEY_SYSTEM_BUTTON = "system_button"
    const val KEY_FLOATING_BUTTON = "floating_button"
    const val KEY_FAB_X = "fab_x"
    const val KEY_FAB_Y = "fab_y"

    // ---- pay mode: pause the accessibility service around payment apps ----
    const val KEY_AUTO_PAUSE = "auto_pause"
    const val KEY_PAUSED_BY_US = "a11y_paused_by_us"
    const val KEY_PAUSED_FOR = "a11y_paused_for"
    const val KEY_PAUSE_PACKAGES = "pause_packages"
    const val KEY_PAUSED_AT = "a11y_paused_at"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Screen off + any slider move = wake. */
    fun wakeOnSlider(ctx: Context) = get(ctx).getBoolean(KEY_WAKE_ON_SLIDER, true)

    /** Screen on + flick and return (2 moves) = screen off. */
    fun sliderLock(ctx: Context) = get(ctx).getBoolean(KEY_SLIDER_LOCK, true)

    /** Screen on + flick, return, flick (3+ moves) = power menu. */
    fun sliderPowerMenu(ctx: Context) = get(ctx).getBoolean(KEY_SLIDER_POWER_MENU, true)

    fun serviceEnabled(ctx: Context) = get(ctx).getBoolean(KEY_SERVICE_ENABLED, false)

    /** Android's own accessibility button: tap = screen off, double tap = power menu. */
    fun systemButton(ctx: Context) = get(ctx).getBoolean(KEY_SYSTEM_BUTTON, true)

    /** AlertPower's draggable overlay button: tap = screen off, hold = power menu. */
    fun floatingButton(ctx: Context) = get(ctx).getBoolean(KEY_FLOATING_BUTTON, false)

    fun setServiceEnabled(ctx: Context, enabled: Boolean) =
        get(ctx).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()

    fun setBoolean(ctx: Context, key: String, value: Boolean) =
        get(ctx).edit().putBoolean(key, value).apply()

    // ---- pay mode ----

    /** Watch the foreground app and pause the accessibility service while a payment app is open. */
    fun autoPause(ctx: Context) = get(ctx).getBoolean(KEY_AUTO_PAUSE, true)

    /** True while AlertPower itself turned its accessibility service off, so turning it back on is AlertPower's job. */
    fun pausedByUs(ctx: Context) = get(ctx).getBoolean(KEY_PAUSED_BY_US, false)

    /** Package name (or "manual") the current pause was for, for status text. */
    fun pausedFor(ctx: Context): String? = get(ctx).getString(KEY_PAUSED_FOR, null)

    /** Wall-clock time the current pause started, so the safety net can be re-armed after a process restart. */
    fun pausedAt(ctx: Context): Long = get(ctx).getLong(KEY_PAUSED_AT, 0L)

    fun setPaused(ctx: Context, paused: Boolean, reason: String?) {
        val e = get(ctx).edit()
            .putBoolean(KEY_PAUSED_BY_US, paused)
            .putString(KEY_PAUSED_FOR, if (paused) reason else null)
        if (paused && !pausedByUs(ctx)) e.putLong(KEY_PAUSED_AT, System.currentTimeMillis())
        if (!paused) e.remove(KEY_PAUSED_AT)
        e.apply()
    }

    /** Apps that pause the accessibility service. The first read seeds the list with the installed known payment apps. */
    fun pausePackages(ctx: Context): Set<String> {
        val p = get(ctx)
        p.getStringSet(KEY_PAUSE_PACKAGES, null)?.let { return it.toSet() }
        val seed = KnownPaymentApps.installed(ctx)
        p.edit().putStringSet(KEY_PAUSE_PACKAGES, seed).apply()
        return seed
    }

    fun setPausePackages(ctx: Context, packages: Set<String>) =
        get(ctx).edit().putStringSet(KEY_PAUSE_PACKAGES, packages.toSet()).apply()
}
