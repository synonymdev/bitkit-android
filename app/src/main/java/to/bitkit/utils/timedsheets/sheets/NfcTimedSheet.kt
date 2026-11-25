package to.bitkit.utils.timedsheets.sheets

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import to.bitkit.data.SettingsStore
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger
import to.bitkit.utils.timedsheets.TimedSheetItem
import javax.inject.Inject

/** Announces NFC payments once, on devices that can read NFC tags. */
class NfcTimedSheet @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
) : TimedSheetItem {
    override val type = TimedSheetType.NFC
    override val priority = type.priority

    override suspend fun shouldShow(): Boolean {
        if (settingsStore.data.first().nfcIntroSeen) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    }

    override suspend fun onShown() {
        Logger.debug("NFC sheet shown", context = TAG)
    }

    override suspend fun onDismissed() {
        // The NFC sheet carries the QuickPay toggle, so the separate QuickPay intro is not needed after it.
        settingsStore.update { it.copy(nfcIntroSeen = true, quickPayIntroSeen = true) }
        Logger.debug("NFC sheet dismissed", context = TAG)
    }

    companion object {
        private const val TAG = "NfcTimedSheet"
    }
}
