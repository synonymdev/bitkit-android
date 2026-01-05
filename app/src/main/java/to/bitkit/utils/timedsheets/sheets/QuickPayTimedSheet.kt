package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.first
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger
import to.bitkit.utils.timedsheets.TimedSheetItem
import javax.inject.Inject

class QuickPayTimedSheet @Inject constructor(
    private val settingsStore: SettingsStore,
    private val walletRepo: WalletRepo,
) : TimedSheetItem {
    override val type = TimedSheetType.QUICK_PAY
    override val priority = 2

    override suspend fun shouldShow(): Boolean {
        val settings = settingsStore.data.first()
        if (settings.quickPayIntroSeen || settings.isQuickPayEnabled) return false

        val hasLightningBalance = walletRepo.balanceState.value.totalLightningSats > 0U
        return hasLightningBalance
    }

    override suspend fun onShown() {
        Logger.debug("QuickPay sheet shown", context = TAG)
    }

    override suspend fun onDismissed() {
        settingsStore.update {
            it.copy(quickPayIntroSeen = true)
        }
        Logger.debug("QuickPay sheet dismissed", context = TAG)
    }

    companion object {
        private const val TAG = "QuickPayTimedSheet"
    }
}
