package app.alertpower

import android.content.Context
import android.content.pm.PackageManager

/**
 * Indian payment and banking apps known to refuse to run while a sideloaded accessibility
 * service is enabled (Paytm "Warning [81004]", Google Pay, BHIM, several bank apps).
 * Only used to seed the user's "pause for these apps" list with whatever is installed.
 */
object KnownPaymentApps {
    val PACKAGES: List<String> = listOf(
        "net.one97.paytm",                            // Paytm
        "com.phonepe.app",                            // PhonePe
        "com.google.android.apps.nbu.paisa.user",     // Google Pay
        "com.google.android.apps.nbu.paisa.merchant", // Google Pay for Business
        "in.org.npci.upiapp",                         // BHIM
        "com.dreamplug.androidapp",                   // CRED
        "com.mobikwik_new",                           // MobiKwik
        "com.freecharge.android",                     // Freecharge
        "com.sbi.lotusintouch",                       // SBI YONO
        "com.sbi.SBIFreedomPlus",                     // SBI YONO Lite
        "com.csam.icici.bank.imobile",                // ICICI iMobile
        "com.snapwork.hdfc",                          // HDFC MobileBanking
        "com.axis.mobile",                            // Axis Mobile
        "com.msf.kbank.mobile",                       // Kotak
    )

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun installed(ctx: Context): Set<String> = PACKAGES.filter { isInstalled(ctx, it) }.toSet()
}
