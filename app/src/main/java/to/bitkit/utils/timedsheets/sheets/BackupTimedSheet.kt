package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.first
import to.bitkit.data.SettingsStore
import to.bitkit.ext.nowMillis
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger
import to.bitkit.utils.timedsheets.ONE_DAY_ASK_INTERVAL_MILLIS
import to.bitkit.utils.timedsheets.TimedSheetItem
import to.bitkit.utils.timedsheets.checkTimeout
import javax.inject.Inject
import kotlin.time.ExperimentalTime

class BackupTimedSheet @Inject constructor(
    private val settingsStore: SettingsStore,
    private val walletRepo: WalletRepo,
) : TimedSheetItem {
    override val type = TimedSheetType.BACKUP
    override val priority = 4

    override suspend fun shouldShow(): Boolean {
        val settings = settingsStore.data.first()
        if (settings.backupVerified) return false

        val hasBalance = walletRepo.balanceState.value.totalSats > 0U
        if (!hasBalance) return false

        return checkTimeout(
            lastIgnoredMillis = settings.backupWarningIgnoredMillis,
            intervalMillis = ONE_DAY_ASK_INTERVAL_MILLIS
        )
    }

    override suspend fun onShown() {
        Logger.debug("Backup sheet shown", context = TAG)
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun onDismissed() {
        val currentTime = nowMillis()
        settingsStore.update {
            it.copy(backupWarningIgnoredMillis = currentTime)
        }
        Logger.debug("Backup sheet dismissed", context = TAG)
    }

    companion object {
        private const val TAG = "BackupTimedSheet"
    }
}
