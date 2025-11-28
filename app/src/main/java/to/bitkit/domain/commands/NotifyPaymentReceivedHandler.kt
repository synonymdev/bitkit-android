package to.bitkit.domain.commands

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NotificationState
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotifyPaymentReceivedHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val activityRepo: ActivityRepo,
    private val currencyRepo: CurrencyRepo,
    private val settingsStore: SettingsStore,
) {
    suspend operator fun invoke(
        command: NotifyPaymentReceived.Command,
    ): Result<NotifyPaymentReceived.Result> = withContext(ioDispatcher) {
        runCatching {
            val shouldShow = when (command) {
                is NotifyPaymentReceived.Command.Lightning -> true
                is NotifyPaymentReceived.Command.Onchain -> {
                    delay(DELAY_FOR_ACTIVITY_SYNC_MS)
                    activityRepo.shouldShowPaymentReceived(command.paymentHashOrTxId, command.sats)
                }
            }

            if (!shouldShow) return@runCatching NotifyPaymentReceived.Result.Skip

            val satsLong = command.sats.toLong()
            val details = NewTransactionSheetDetails(
                type = when (command) {
                    is NotifyPaymentReceived.Command.Lightning -> NewTransactionSheetType.LIGHTNING
                    is NotifyPaymentReceived.Command.Onchain -> NewTransactionSheetType.ONCHAIN
                },
                direction = NewTransactionSheetDirection.RECEIVED,
                paymentHashOrTxId = command.paymentHashOrTxId,
                sats = satsLong,
            )

            if (command.includeNotification) {
                val notification = buildNotificationContent(satsLong)
                NotifyPaymentReceived.Result.ShowNotification(details, notification)
            } else {
                NotifyPaymentReceived.Result.ShowSheet(details)
            }
        }.onFailure { e ->
            Logger.error("Failed to process payment notification", e, context = TAG)
        }
    }

    private suspend fun buildNotificationContent(sats: Long): NotificationState {
        val settings = settingsStore.data.first()
        val title = context.getString(R.string.notification_received_title)
        val body = if (settings.showNotificationDetails) {
            formatNotificationAmount(sats, settings)
        } else {
            context.getString(R.string.notification_received_body_hidden)
        }
        return NotificationState(title, body)
    }

    private fun formatNotificationAmount(sats: Long, settings: SettingsData): String {
        val converted = currencyRepo.convertSatsToFiat(sats).getOrNull()

        val amountText = converted?.let {
            val btcDisplay = it.bitcoinDisplay(settings.displayUnit)
            if (settings.primaryDisplay == PrimaryDisplay.BITCOIN) {
                "${btcDisplay.symbol} ${btcDisplay.value} (${it.symbol}${it.formatted})"
            } else {
                "${it.symbol}${it.formatted} (${btcDisplay.symbol} ${btcDisplay.value})"
            }
        } ?: "$BITCOIN_SYMBOL ${sats.formatToModernDisplay()}"

        return context.getString(R.string.notification_received_body_amount, amountText)
    }

    companion object {
        const val TAG = "NotifyPaymentReceivedHandler"
        private const val DELAY_FOR_ACTIVITY_SYNC_MS = 500L
    }
}
