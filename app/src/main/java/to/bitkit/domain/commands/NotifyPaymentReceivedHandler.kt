package to.bitkit.domain.commands

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.msatCeilOf
import to.bitkit.repositories.ActivityRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotifyPaymentReceivedHandler @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val activityRepo: ActivityRepo,
    private val receivedNotificationContent: ReceivedNotificationContent,
) {
    private val presentationClaimsLock = Any()
    private val presentationClaims = mutableSetOf<String>()

    suspend operator fun invoke(
        command: NotifyPaymentReceived.Command,
    ): Result<NotifyPaymentReceived.Result> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (isPresentationClaimed(command)) return@runSuspendCatching NotifyPaymentReceived.Result.Skip

            val shouldShow = when (command) {
                is NotifyPaymentReceived.Command.Lightning -> shouldShowLightning(command)
                is NotifyPaymentReceived.Command.Onchain -> shouldShowOnchain(command)
            }

            if (!shouldShow) return@runSuspendCatching NotifyPaymentReceived.Result.Skip

            val details = buildSheetDetails(command)

            if (command.includeNotification) {
                val notification = receivedNotificationContent.build(details.sats)
                NotifyPaymentReceived.Result.ShowNotification(details, notification)
            } else {
                NotifyPaymentReceived.Result.ShowSheet(details)
            }
        }.onFailure { e ->
            Logger.error("Failed to process payment notification", e, context = TAG)
        }
    }

    fun claimPresentation(command: NotifyPaymentReceived.Command): Boolean = claimPresentation(command) { true }

    fun claimPresentation(
        command: NotifyPaymentReceived.Command,
        canPresent: () -> Boolean,
    ): Boolean {
        val key = presentationKey(command) ?: return false
        return synchronized(presentationClaimsLock) {
            canPresent() && presentationClaims.add(key)
        }
    }

    suspend fun present(
        command: NotifyPaymentReceived.Command,
        canPresent: () -> Boolean = { true },
        block: () -> Unit,
    ): Boolean {
        if (!claimPresentation(command, canPresent)) return false
        block()
        recordPresentation(command)
        return true
    }

    suspend fun recordPresentation(command: NotifyPaymentReceived.Command) {
        withContext(ioDispatcher) {
            runSuspendCatching { markAsSeen(command) }
                .onFailure { Logger.error("Failed to mark payment notification as presented", it, context = TAG) }
        }
    }

    private fun isPresentationClaimed(command: NotifyPaymentReceived.Command): Boolean {
        val key = presentationKey(command) ?: return false
        return synchronized(presentationClaimsLock) { key in presentationClaims }
    }

    private fun presentationKey(command: NotifyPaymentReceived.Command): String? = when (command) {
        is NotifyPaymentReceived.Command.Lightning -> command.event.paymentId?.let { "lightning:$it" }
        is NotifyPaymentReceived.Command.Onchain -> "onchain:${command.event.txid}"
    }

    private suspend fun shouldShowLightning(command: NotifyPaymentReceived.Command.Lightning): Boolean {
        val paymentId = command.event.paymentId ?: return false
        delay(DELAY_FOR_ACTIVITY_SYNC_MS)
        return !activityRepo.isActivitySeen(paymentId)
    }

    private suspend fun shouldShowOnchain(command: NotifyPaymentReceived.Command.Onchain): Boolean {
        activityRepo.handleOnchainTransactionReceived(command.event.txid, command.event.details)
        if (command.event.details.amountSats <= 0) return false

        delay(DELAY_FOR_ACTIVITY_SYNC_MS)
        val shouldShowSheet = retryShouldShowReceivedSheet(
            command.event.txid,
            command.event.details.amountSats.toULong(),
        )
        return shouldShowSheet
    }

    private suspend fun markAsSeen(command: NotifyPaymentReceived.Command) {
        when (command) {
            is NotifyPaymentReceived.Command.Lightning -> {
                val paymentId = command.event.paymentId ?: return
                activityRepo.markActivityAsSeen(paymentId)
            }

            is NotifyPaymentReceived.Command.Onchain -> activityRepo.markOnchainActivityAsSeen(command.event.txid)
        }
    }

    private suspend fun retryShouldShowReceivedSheet(txid: String, amountSats: ULong): Boolean {
        repeat(MAX_RETRIES) {
            if (activityRepo.shouldShowReceivedSheet(txid, amountSats)) return true
            delay(RETRY_DELAY_MS)
        }
        return activityRepo.shouldShowReceivedSheet(txid, amountSats)
    }

    private fun buildSheetDetails(command: NotifyPaymentReceived.Command) = NewTransactionSheetDetails(
        type = when (command) {
            is NotifyPaymentReceived.Command.Lightning -> NewTransactionSheetType.LIGHTNING
            is NotifyPaymentReceived.Command.Onchain -> NewTransactionSheetType.ONCHAIN
        },
        direction = NewTransactionSheetDirection.RECEIVED,
        paymentHashOrTxId = when (command) {
            is NotifyPaymentReceived.Command.Lightning -> command.event.paymentHash
            is NotifyPaymentReceived.Command.Onchain -> command.event.txid
        },
        sats = when (command) {
            is NotifyPaymentReceived.Command.Lightning -> msatCeilOf(command.event.amountMsat).toLong()
            is NotifyPaymentReceived.Command.Onchain -> command.event.details.amountSats
        },
    )

    companion object {
        const val TAG = "NotifyPaymentReceivedHandler"

        /**
         * Delay after syncing onchain transaction to allow the database to fully process
         * the transaction before checking for RBF replacement or channel closure.
         */
        private const val DELAY_FOR_ACTIVITY_SYNC_MS = 500L
        private const val RETRY_DELAY_MS = 300L
        private const val MAX_RETRIES = 3
    }
}
