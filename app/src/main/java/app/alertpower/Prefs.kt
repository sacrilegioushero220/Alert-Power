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
}
