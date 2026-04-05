package to.bitkit.fcm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.lightningdevkit.ldknode.Event
import to.bitkit.App
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.di.json
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.toUserMessage
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BlocktankNotificationType
import to.bitkit.models.MSat
import to.bitkit.models.BlocktankNotificationType.cjitPaymentArrived
import to.bitkit.models.BlocktankNotificationType.incomingHtlc
import to.bitkit.models.BlocktankNotificationType.mutualClose
import to.bitkit.models.BlocktankNotificationType.orderPaymentConfirmed
import to.bitkit.models.BlocktankNotificationType.wakeToTimeout
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NotificationDetails
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import to.bitkit.utils.measured
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("LongParameterList")
@HiltWorker
class WakeNodeWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
    private val settingsStore: SettingsStore,
    private val cacheStore: CacheStore,
) : CoroutineWorker(appContext, workerParams) {
    private var bestAttemptContent: NotificationDetails? = null

    private var notificationType: BlocktankNotificationType? = null
    private var notificationPayload: JsonObject? = null

    private val timeout = 2.minutes
    private val deliverSignal = CompletableDeferred<Unit>()

    override suspend fun doWork(): Result {
        Logger.debug("Node wakeup from notification…", context = TAG)

        notificationType = workerParams.inputData.getString("type")?.let { BlocktankNotificationType.valueOf(it) }
        notificationPayload = workerParams.inputData.getString("payload")?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }

        Logger.debug("notification type: $notificationType", context = TAG)
        Logger.debug("notification payload: $notificationPayload", context = TAG)

        if (notificationType == null) {
            Logger.warn("Notification type is null, proceeding with node wake", context = TAG)
        }

        return runCatching {
            measured(label = "doWork", context = TAG) {
                lightningRepo.start(
                    walletIndex = 0,
                    timeout = timeout,
                    eventHandler = { event -> handleLdkEvent(event) }
                )

                // Once node is started, handle the manual channel opening if needed
                if (notificationType == orderPaymentConfirmed) {
                    val orderId = (notificationPayload?.get("orderId") as? JsonPrimitive)?.contentOrNull

                    if (orderId == null) {
                        Logger.error("Missing orderId", context = TAG)
                    } else {
                        Logger.info("Open channel request for order $orderId", context = TAG)
                        blocktankRepo.openChannel(orderId).onFailure {
                            Logger.error("Failed to open channel", it, context = TAG)
                            bestAttemptContent = NotificationDetails(
                                title = appContext.getString(R.string.notification__channel_open_failed_title),
                                body = it.message ?: appContext.getString(R.string.common__error_body),
                            )
                            deliver()
                        }
                    }
                }
            }
            // Stops node on timeout & avoids notification replay by OS
            withTimeout(timeout) { deliverSignal.await() }
        }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { e ->
                    if (e is CancellationException) {
                        Logger.debug("Work cancelled", context = TAG)
                        return@fold Result.failure(workDataOf("Reason" to "Cancelled"))
                    }

                    val reason = e.message ?: appContext.getString(R.string.common__error_body)

                    bestAttemptContent = NotificationDetails(
                        title = appContext.getString(R.string.notification__lightning_error_title),
                        body = reason,
                    )
                    Logger.error("Lightning error", e, context = TAG)
                    deliver()

                    Result.failure(workDataOf("Reason" to reason))
                }
            )
    }

    /**
     * Listens for LDK events and delivers the notification if the event matches the notification type.
     * @param event The LDK event to check.
     */
    private suspend fun handleLdkEvent(event: Event) {
        val showDetails = settingsStore.data.first().showNotificationDetails
        val hiddenBody = appContext.getString(R.string.notification__received__body_hidden)
        when (event) {
            is Event.PaymentReceived -> onPaymentReceived(event, showDetails, hiddenBody)

            is Event.ChannelPending -> {
                bestAttemptContent = NotificationDetails(
                    title = appContext.getString(R.string.notification__channel_opened_title),
                    body = appContext.getString(R.string.notification__channel_pending_body),
                )
                // Don't deliver, give a chance for channelReady event to update the content if it's a turbo channel
            }

            is Event.ChannelReady -> onChannelReady(event, showDetails, hiddenBody)
            is Event.ChannelClosed -> onChannelClosed(event)

            is Event.PaymentFailed -> {
                bestAttemptContent = NotificationDetails(
                    title = appContext.getString(R.string.notification__payment_failed_title),
                    body = "⚡ ${event.reason.toUserMessage(appContext)}",
                )

                if (notificationType == wakeToTimeout) {
                    deliver()
                }
            }

            else -> Unit
        }
    }

    private suspend fun onChannelClosed(event: Event.ChannelClosed) {
        bestAttemptContent = when (notificationType) {
            mutualClose -> NotificationDetails(
                title = appContext.getString(R.string.notification__channel_closed__title),
                body = appContext.getString(R.string.notification__channel_closed__mutual_body),
            )

            orderPaymentConfirmed -> NotificationDetails(
                title = appContext.getString(R.string.notification__channel_open_bg_failed_title),
                body = appContext.getString(R.string.notification__please_try_again_body),
            )

            else -> NotificationDetails(
                title = appContext.getString(R.string.notification__channel_closed__title),
                body = appContext.getString(R.string.notification__channel_closed__reason_body, event.reason),
            )
        }

        deliver()
    }

    private suspend fun onPaymentReceived(
        event: Event.PaymentReceived,
        showDetails: Boolean,
        hiddenBody: String,
    ) {
        val sats = MSat(event.amountMsat).ceil()
        // Save for UI to pick up
        cacheStore.setBackgroundReceive(
            NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.RECEIVED,
                paymentHashOrTxId = event.paymentHash,
                sats = sats.toLong(),
            )
        )
        val content = if (showDetails) "$BITCOIN_SYMBOL $sats" else hiddenBody
        bestAttemptContent = NotificationDetails(
            title = appContext.getString(R.string.notification__received__title),
            body = content,
        )
        if (notificationType == incomingHtlc) {
            deliver()
        }
    }

    private suspend fun onChannelReady(
        event: Event.ChannelReady,
        showDetails: Boolean,
        hiddenBody: String,
    ) {
        val viaNewChannel = appContext.getString(R.string.notification__received__body_channel)
        if (notificationType == cjitPaymentArrived) {
            bestAttemptContent = NotificationDetails(
                title = appContext.getString(R.string.notification__received__title),
                body = viaNewChannel,
            )

            lightningRepo.getChannels()?.find { it.channelId == event.channelId }?.let { channel ->
                val sats = channel.amountOnClose
                val content = if (showDetails) "$BITCOIN_SYMBOL $sats" else hiddenBody
                bestAttemptContent = NotificationDetails(
                    title = content,
                    body = viaNewChannel,
                )
                val cjitEntry = channel.let { blocktankRepo.getCjitEntry(it) }
                if (cjitEntry != null) {
                    // Save for UI to pick up
                    cacheStore.setBackgroundReceive(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.RECEIVED,
                            sats = sats.toLong(),
                        )
                    )
                    activityRepo.insertActivityFromCjit(cjitEntry = cjitEntry, channel = channel)
                }
            }
        } else if (notificationType == orderPaymentConfirmed) {
            bestAttemptContent = NotificationDetails(
                title = appContext.getString(R.string.notification__channel_opened_title),
                body = appContext.getString(R.string.notification__channel_ready_body),
            )
        }
        deliver()
    }

    private suspend fun deliver() {
        // Send notification first
        bestAttemptContent?.run {
            appContext.pushNotification(title, body)
            Logger.info("Delivered notification", context = TAG)
        }

        // Delay briefly to allow app to come to foreground if user clicked notification
        delay(1.seconds)

        // Only stop node if app is not in foreground
        // LightningNodeService will keep node running in background when notifications are enabled
        if (App.currentActivity?.value == null) {
            Logger.debug("App in background, stopping node after notification delivery", context = TAG)
            lightningRepo.stop()
        } else {
            Logger.debug("App in foreground, keeping node running", context = TAG)
        }

        deliverSignal.complete(Unit)
    }

    companion object {
        private const val TAG = "WakeNodeWorker"
    }
}
