package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.first
import to.bitkit.data.SettingsStore
import to.bitkit.ext.nowMillis
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger
import to.bitkit.utils.timedsheets.ONE_WEEK_ASK_INTERVAL_MILLIS
import to.bitkit.utils.timedsheets.TimedSheetItem
import to.bitkit.utils.timedsheets.checkTimeout
import javax.inject.Inject
import kotlin.time.ExperimentalTime

class NotificationsTimedSheet @Inject constructor(
    private val settingsStore: SettingsStore,
    private val walletRepo: WalletRepo,
) : TimedSheetItem {
    override val type = TimedSheetType.NOTIFICATIONS
    override val priority = 3

    override suspend fun shouldShow(): Boolean {
        val settings = settingsStore.data.first()
        if (settings.notificationsGranted) return false
        if (walletRepo.balanceState.value.totalLightningSats == 0UL) return false

        return checkTimeout(
            lastIgnoredMillis = settings.notificationsIgnoredMillis,
            intervalMillis = ONE_WEEK_ASK_INTERVAL_MILLIS
        )
    }

    override suspend fun onShown() {
        Logger.debug("Notifications sheet shown", context = TAG)
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun onDismissed() {
        val currentTime = nowMillis()
        settingsStore.update {
            it.copy(notificationsIgnoredMillis = currentTime)
        }
        Logger.debug("Notifications sheet dismissed", context = TAG)
    }

    companion object {
        private const val TAG = "NotificationsTimedSheet"
    }
}
