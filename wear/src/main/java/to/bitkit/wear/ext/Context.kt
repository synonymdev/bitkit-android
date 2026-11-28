package to.bitkit.wear.ext

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.nfc.NfcAdapter
import android.provider.Settings
import to.bitkit.wear.utils.Logger

private const val TAG = "Context"

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

val Context.connectivityManager: ConnectivityManager
    get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

/** Whether the wearer turned animations off, so motion that only decorates should stay still. */
val Context.areAnimationsDisabled: Boolean
    get() = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** The watch's NFC adapter, or null when it has no NFC hardware. */
val Context.nfcAdapter: NfcAdapter?
    get() = NfcAdapter.getDefaultAdapter(this)

/** Opens the NFC settings, or the settings home on a watch whose settings have no NFC screen. */
fun Context.openNfcSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        .recoverCatching {
            if (it !is ActivityNotFoundException) throw it
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        .onFailure { Logger.warn("Failed to open NFC settings", it, context = TAG) }
}
