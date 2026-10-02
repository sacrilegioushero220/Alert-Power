package app.alertpower

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * A small round power button drawn by the accessibility service as a
 * TYPE_ACCESSIBILITY_OVERLAY window, which needs no extra permission.
 *
 *   tap             -> [onTap]   (screen off)
 *   press and hold  -> [onHold]  (power menu)
 *   drag            -> move, snaps to the nearest side, position is remembered
 */
class FloatingButton(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
    private val onHold: () -> Unit,
) {
    companion object {
        private const val TAG = "FloatingButton"
        private const val SIZE_DP = 54
        private const val MARGIN_DP = 12
        private const val HOLD_MS = 500L
        private const val ACCENT = 0xFF22D3EE.toInt()
        private const val FILL = 0xE60B1220.toInt()
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val size = (SIZE_DP * density).toInt()
    private val margin = (MARGIN_DP * density).toInt()
    private val slop = ViewConfiguration.get(service).scaledTouchSlop

    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var view: ImageView? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var holdFired = false

    val isShown: Boolean get() = view != null

    private val holdRunnable = Runnable {
        holdFired = true
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHold()
    }

    fun show() {
        if (view != null) return
        val dm = service.resources.displayMetrics
        val prefs = Prefs.get(service)
        params.x = prefs.getInt(Prefs.KEY_FAB_X, dm.widthPixels - size - margin)
        params.y = prefs.getInt(Prefs.KEY_FAB_Y, (dm.heightPixels * 0.42f).toInt())

        val iv = ImageView(service).apply {
            setImageResource(R.drawable.ic_notification)
            imageTintList = ColorStateList.valueOf(ACCENT)
            val pad = (13 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FILL)
                setStroke((1.5f * density).toInt(), 0xB322D3EE.toInt())
            }
            elevation = 8 * density
            contentDescription = "AlertPower power button"
            setOnTouchListener(touchListener)
        }
        try {
            wm.addView(iv, params)
            view = iv
        } catch (e: Exception) {
            Log.e(TAG, "could not add overlay", e)
        }
    }

    fun hide() {
        handler.removeCallbacks(holdRunnable)
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
    }

    private val touchListener = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                startX = params.x
                startY = params.y
                dragging = false
                holdFired = false
                handler.postDelayed(holdRunnable, HOLD_MS)
                v.alpha = 0.75f
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    handler.removeCallbacks(holdRunnable)
                }
                if (dragging) {
                    params.x = (startX + dx).toInt()
                    params.y = (startY + dy).toInt()
                    try {
                        wm.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                v.alpha = 1f
                when {
                    dragging -> snapAndSave(v)
                    !holdFired && e.actionMasked == MotionEvent.ACTION_UP -> {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onTap()
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun snapAndSave(v: View) {
        val dm = service.resources.displayMetrics
        params.x = if (params.x + size / 2 < dm.widthPixels / 2) margin else dm.widthPixels - size - margin
        params.y = params.y.coerceIn(margin, dm.heightPixels - size - margin)
        try {
            wm.updateViewLayout(v, params)
        } catch (_: Exception) {
        }
        Prefs.get(service).edit().putInt(Prefs.KEY_FAB_X, params.x).putInt(Prefs.KEY_FAB_Y, params.y).apply()
    }
}
