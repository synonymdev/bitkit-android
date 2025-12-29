package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.first
import to.bitkit.data.SettingsStore
import to.bitkit.ext.nowMillis
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger
import to.bitkit.utils.timedsheets.ONE_DAY_ASK_INTERVAL_MILLIS
import to.bitkit.utils.timedsheets.TimedSheetItem
import to.bitkit.utils.timedsheets.checkTimeout
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.time.ExperimentalTime

class HighBalanceTimedSheet @Inject constructor(
    private val settingsStore: SettingsStore,
    private val walletRepo: WalletRepo,
    private val currencyRepo: CurrencyRepo,
) : TimedSheetItem {
    override val type = TimedSheetType.HIGH_BALANCE
    override val priority = 1

    override suspend fun shouldShow(): Boolean {
        val settings = settingsStore.data.first()

        val totalOnChainSats = walletRepo.balanceState.value.totalSats
        val balanceUsd = satsToUsd(totalOnChainSats) ?: return false
        val thresholdReached = balanceUsd > BigDecimal(BALANCE_THRESHOLD_USD)

        if (!thresholdReached) {
            settingsStore.update { it.copy(balanceWarningTimes = 0) }
            return false
        }

        val belowMaxWarnings = settings.balanceWarningTimes < MAX_WARNINGS

        return checkTimeout(
            lastIgnoredMillis = settings.balanceWarningIgnoredMillis,
            intervalMillis = ONE_DAY_ASK_INTERVAL_MILLIS,
            additionalCondition = belowMaxWarnings
        )
    }

    override suspend fun onShown() {
        Logger.debug("High balance sheet shown", context = TAG)
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun onDismissed() {
        val currentTime = nowMillis()
        settingsStore.update {
            it.copy(
                balanceWarningTimes = it.balanceWarningTimes + 1,
                balanceWarningIgnoredMillis = currentTime,
            )
        }
        Logger.debug("High balance sheet dismissed", context = TAG)
    }

    private fun satsToUsd(sats: ULong): BigDecimal? {
        val converted = currencyRepo.convertSatsToFiat(sats = sats.toLong(), currency = "USD").getOrNull()
        return converted?.value
    }

    companion object {
        private const val TAG = "HighBalanceTimedSheet"
        private const val BALANCE_THRESHOLD_USD = 500L
        private const val MAX_WARNINGS = 3
    }
}
