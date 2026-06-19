package to.bitkit.domain.commands

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
class NotifyChannelReadyHandler @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
    private val receivedNotificationContent: ReceivedNotificationContent,
) {
    companion object {
        const val TAG = "NotifyChannelReadyHandler"
    }

    suspend operator fun invoke(
        command: NotifyChannelReady.Command,
    ): Result<NotifyChannelReady.Result> = withContext(ioDispatcher) {
        runSuspendCatching {
            val channel = lightningRepo.getChannels()
                ?.find { it.channelId == command.event.channelId }
                ?: return@runSuspendCatching NotifyChannelReady.Result.Skip

            val cjitEntry = blocktankRepo.getCjitEntry(channel)
                ?: return@runSuspendCatching NotifyChannelReady.Result.Skip

            val inserted = activityRepo.insertActivityFromCjit(cjitEntry = cjitEntry, channel = channel)
                .getOrDefault(false)
            if (!inserted) return@runSuspendCatching NotifyChannelReady.Result.Duplicate

            val sats = channel.amountOnClose.toLong()

            val details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = sats,
            )

            if (command.includeNotification) {
                val notification = receivedNotificationContent.build(sats)
                NotifyChannelReady.Result.ShowNotification(details, notification)
            } else {
                NotifyChannelReady.Result.ShowSheet(details)
            }
        }.onFailure {
            Logger.error("Failed to process channel ready notification", it, context = TAG)
        }
    }
}
