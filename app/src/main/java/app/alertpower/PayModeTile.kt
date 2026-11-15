package app.alertpower

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * Quick settings tile for pay mode.
 *
 *  - ACTIVE      "Paused": AlertPower turned its accessibility service off for a payment.
 *                Tap = turn it back on now.
 *  - INACTIVE    "Armed": the service is on. Tap = pause it before opening a payment app.
 *  - INACTIVE    "Off": the service is off and not by us, nothing to pause. Tap = open AlertPower.
 *                (Not STATE_UNAVAILABLE: Android 14 delivers no taps to an unavailable tile.)
 *
 * Declared as an active tile, so the system only binds it on tap or when [refresh] is called;
 * SliderWakeService calls [refresh] whenever the pause state changes.
 */
class PayModeTile : TileService() {

    companion object {
        private const val TAG = "PayModeTile"

        /** Asks the system to rebind the tile so it shows the current state. Harmless when the tile is not added. */
        fun refresh(ctx: Context) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, PayModeTile::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "requestListeningState failed", e)
            }
        }
    }

    private val pauseListener: () -> Unit = { updateState() }

    override fun onStartListening() {
        super.onStartListening()
        PaymentPause.addListener(pauseListener)
        PaymentPause.ensureArmed(this)
        updateState()
    }

    override fun onStopListening() {
        PaymentPause.removeListener(pauseListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!PaymentPause.isPaused(this) && !A11yToggle.isOn(this)) {
            Log.i(TAG, "tap with the service off -> open app")
            openApp()
            return
        }
        val ok = PaymentPause.toggle(this)
        Log.i(TAG, "tap -> toggle ok=$ok paused=${PaymentPause.isPaused(this)}")
        if (!ok) {
            Toast.makeText(this, "Could not turn the service back on. Finish the adb setup in AlertPower.", Toast.LENGTH_LONG).show()
        }
        updateState()
    }

    private fun updateState() {
        val tile = qsTile ?: return
        // Never STATE_UNAVAILABLE: Android 14 does not deliver taps to an unavailable tile, and a tap
        // on the "Off" tile should open AlertPower.
        val paused = PaymentPause.isPaused(this)
        val on = A11yToggle.isOn(this)
        tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Pay mode"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                paused -> "Paused"
                on -> "Armed"
                else -> "Off"
            }
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
