package app.alertpower

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Invisible trampoline behind the "open Paytm through AlertPower" chips and home screen
 * shortcuts: pauses the accessibility service, launches the payment app, finishes.
 * The service is already off before the payment app starts, so even an app that checks at
 * launch passes. Paytm checks later (when the amount screen opens), so for it auto-pause
 * by foreground watching is enough; this path is the belt and braces.
 *
 * Intent: action [ACTION], String extra [EXTRA_PACKAGE]. Not exported; pinned shortcuts are
 * started by the system on the app's behalf.
 */
class PayLaunchActivity : Activity() {

    companion object {
        private const val TAG = "PayLaunchActivity"
        const val ACTION = "app.alertpower.action.PAY_LAUNCH"
        const val EXTRA_PACKAGE = "package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
        if (pkg == null) {
            Log.w(TAG, "launched without a package")
            Toast.makeText(this, "No app to open", Toast.LENGTH_SHORT).show()
        } else if (!launch(pkg)) {
            Log.w(TAG, "could not open $pkg")
            Toast.makeText(this, "Could not open $pkg", Toast.LENGTH_SHORT).show()
        } else {
            Log.i(TAG, "paused and opened $pkg")
        }
        finish()
    }

    private fun launch(pkg: String): Boolean = try {
        PaymentPause.pauseAndLaunch(this, pkg)
    } catch (e: Exception) {
        Log.e(TAG, "launch of $pkg failed", e)
        false
    }
}
