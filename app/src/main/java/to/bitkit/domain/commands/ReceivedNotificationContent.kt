package to.bitkit.domain.commands

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.NotificationDetails
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.CurrencyRepo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the user-facing "Payment Received" notification content, applying the same currency
 * formatting rules everywhere a received payment is surfaced (foreground service, in-app handler,
 * and the background [to.bitkit.fcm.WakeNodeWorker] push path).
 */
@Singleton
class ReceivedNotificationContent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val currencyRepo: CurrencyRepo,
    private val settingsStore: SettingsStore,
) {
    suspend fun build(sats: Long): NotificationDetails {
        val settings = settingsStore.data.first()
        val title = context.getString(R.string.notification__received__title)
        val body = formatAmount(sats, settings)
        return NotificationDetails(title, body)
    }

    private fun formatAmount(sats: Long, settings: SettingsData): String {
        val converted = currencyRepo.convertSatsToFiat(sats).getOrNull()

        val amountText = converted?.let {
            val btcDisplay = it.bitcoinDisplay(settings.displayUnit)
            if (settings.primaryDisplay == PrimaryDisplay.BITCOIN) {
                "${btcDisplay.symbol} ${btcDisplay.value} (${it.formattedWithSymbol()})"
            } else {
                "${it.formattedWithSymbol()} (${btcDisplay.symbol} ${btcDisplay.value})"
            }
        } ?: "$BITCOIN_SYMBOL ${sats.formatToModernDisplay()}"

        return context.getString(R.string.notification__received__body_amount, amountText)
    }
}
