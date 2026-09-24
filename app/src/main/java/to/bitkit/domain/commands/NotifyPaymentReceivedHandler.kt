package to.bitkit.domain.commands

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.msatCeilOf
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.services.MigrationService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Suppress("LongParameterList")
@Singleton
class NotifyPaymentReceivedHandler @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val activityRepo: ActivityRepo,
    private val backupRepo: BackupRepo,
    private val migrationService: MigrationService,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
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
        is NotifyPaymentReceived.Command.Onchain -> "onchain:${command.txid}"
    }

    private suspend fun shouldShowLightning(command: NotifyPaymentReceived.Command.Lightning): Boolean {
        val paymentId = command.event.paymentId ?: return false
        delay(DELAY_FOR_ACTIVITY_SYNC_MS)
        return !activityRepo.isActivitySeen(paymentId)
    }

    private suspend fun shouldShowOnchain(command: NotifyPaymentReceived.Command.Onchain): Boolean {
        if (command.isConfirmedOnly) {
            if (command.details.amountSats <= 0) return false
            if (!canShowConfirmedOnly(command)) return false
            applyConfirmationIfMissing(command)
        } else {
            activityRepo.handleOnchainTransactionReceived(command.txid, command.details)
            if (command.details.amountSats <= 0) return false
        }

        if (settingsStore.data.first().pendingRestoreActivitySeen) {
            Logger.debug("Skipping onchain receive '${command.txid}' until the first sync after restore", context = TAG)
            return false
        }

        delay(DELAY_FOR_ACTIVITY_SYNC_MS)
        val shouldShowSheet = retryShouldShowReceivedSheet(
            command.txid,
            command.details.amountSats.toULong(),
        )
        return shouldShowSheet
    }

    private suspend fun applyConfirmationIfMissing(command: NotifyPaymentReceived.Command.Onchain) {
        if (activityRepo.getOnchainActivityByTxId(command.txid)?.confirmed == true) {
            Logger.debug("Skipping confirmed activity update for '${command.txid}', already applied", context = TAG)
            return
        }
        activityRepo.handleOnchainTransactionConfirmed(command.txid, command.details)
    }

    private suspend fun canShowConfirmedOnly(command: NotifyPaymentReceived.Command.Onchain): Boolean {
        val confirmationTime = command.confirmationTime ?: return false
        if (backupRepo.isRestoring.value) {
            Logger.debug("Skipping confirmed-only receive '${command.txid}' during restore", context = TAG)
            return false
        }
        if (migrationService.isShowingMigrationLoading.value || migrationService.needsPostMigrationSync()) {
            Logger.debug("Skipping confirmed-only receive '${command.txid}' during migration", context = TAG)
            return false
        }
        val age = nowMillis(clock).milliseconds - confirmationTime.toLong().seconds
        if (age.absoluteValue > MAX_CONFIRMED_ONLY_AGE) {
            Logger.debug(
                "Skipping confirmed-only receive '${command.txid}' confirmed at '$confirmationTime'",
                context = TAG,
            )
            return false
        }
        return true
    }

    private suspend fun markAsSeen(command: NotifyPaymentReceived.Command) {
        when (command) {
            is NotifyPaymentReceived.Command.Lightning -> {
                val paymentId = command.event.paymentId ?: return
                activityRepo.markActivityAsSeen(paymentId)
            }

            is NotifyPaymentReceived.Command.Onchain -> activityRepo.markOnchainActivityAsSeen(command.txid)
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
            is NotifyPaymentReceived.Command.Onchain -> command.txid
        },
        sats = when (command) {
            is NotifyPaymentReceived.Command.Lightning -> msatCeilOf(command.event.amountMsat).toLong()
            is NotifyPaymentReceived.Command.Onchain -> command.details.amountSats
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

        /**
         * Max distance between a confirmed-only transaction's block timestamp and the device clock for it to
         * count as a new receive. Older confirmations, such as those replayed by a full wallet scan after a
         * restore, stay silent. The block timestamp is used instead of the node's best block height, which
         * only advances with the lightning wallet sync and can lag the onchain sync that emits the event.
         * The distance is absolute because block timestamps and device clocks can run ahead of each other.
         */
        private val MAX_CONFIRMED_ONLY_AGE = 1.hours
    }
}
