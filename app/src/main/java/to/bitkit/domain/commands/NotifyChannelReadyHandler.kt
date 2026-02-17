package to.bitkit.domain.commands

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NotificationDetails
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
class NotifyChannelReadyHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
    private val currencyRepo: CurrencyRepo,
    private val settingsStore: SettingsStore,
) {
    companion object {
        const val TAG = "NotifyChannelReadyHandler"
    }

    suspend operator fun invoke(
        command: NotifyChannelReady.Command,
    ): Result<NotifyChannelReady.Result> = withContext(ioDispatcher) {
        runCatching {
            val channel = lightningRepo.getChannels()
                ?.find { it.channelId == command.event.channelId }
                ?: return@runCatching NotifyChannelReady.Result.Skip

            val cjitEntry = blocktankRepo.getCjitEntry(channel)
                ?: return@runCatching NotifyChannelReady.Result.Skip

            val sats = channel.amountOnClose.toLong()
            activityRepo.insertActivityFromCjit(cjitEntry = cjitEntry, channel = channel)

            val details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = sats,
            )

            if (command.includeNotification) {
                val notification = buildNotificationContent(sats)
                NotifyChannelReady.Result.ShowNotification(details, notification)
            } else {
                NotifyChannelReady.Result.ShowSheet(details)
            }
        }.onFailure {
            Logger.error("Failed to process channel ready notification", it, context = TAG)
        }
    }

    private suspend fun buildNotificationContent(sats: Long): NotificationDetails {
        val settings = settingsStore.data.first()
        val title = context.getString(R.string.notification__received__title)
        val body = if (settings.showNotificationDetails) {
            formatNotificationAmount(sats, settings)
        } else {
            context.getString(R.string.notification__received__body_hidden)
        }
        return NotificationDetails(title, body)
    }

    private fun formatNotificationAmount(sats: Long, settings: SettingsData): String {
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
