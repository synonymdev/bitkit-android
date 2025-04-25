package to.bitkit.fcm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.lightningdevkit.ldknode.Event
import to.bitkit.di.json
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.blocktank.BlocktankNotificationType
import to.bitkit.models.blocktank.BlocktankNotificationType.cjitPaymentArrived
import to.bitkit.models.blocktank.BlocktankNotificationType.incomingHtlc
import to.bitkit.models.blocktank.BlocktankNotificationType.mutualClose
import to.bitkit.models.blocktank.BlocktankNotificationType.orderPaymentConfirmed
import to.bitkit.models.blocktank.BlocktankNotificationType.wakeToTimeout
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.CoreService
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import to.bitkit.utils.withPerformanceLogging
import kotlin.time.Duration.Companion.minutes

@HiltWorker
class WakeNodeWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val self = this

    class VisibleNotification(var title: String = "", var body: String = "")

    private var bestAttemptContent: VisibleNotification? = VisibleNotification()

    private var notificationType: BlocktankNotificationType? = null
    private var notificationPayload: JsonObject? = null

    private val timeout = 2.minutes
    private val deliverSignal = CompletableDeferred<Unit>()

    override suspend fun doWork(): Result {
        Logger.debug("Node wakeup from notification…")

        notificationType = workerParams.inputData.getString("type")?.let { BlocktankNotificationType.valueOf(it) }
        notificationPayload = workerParams.inputData.getString("payload")?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }

        Logger.debug("${this::class.simpleName} notification type: $notificationType")
        Logger.debug("${this::class.simpleName} notification payload: $notificationPayload")

        try {
            withPerformanceLogging {
                lightningRepo.start(walletIndex = 0, timeout= timeout) { event -> handleLdkEvent(event) }
                lightningRepo.connectToTrustedPeers()

                // Once node is started, handle the manual channel opening if needed
                if (self.notificationType == orderPaymentConfirmed) {
                    val orderId = (notificationPayload?.get("orderId") as? JsonPrimitive)?.contentOrNull

                    if (orderId == null) {
                        Logger.error("Missing orderId")
                    } else {
                        try {
                            Logger.info("Open channel request for order $orderId")
                            coreService.blocktank.open(orderId = orderId)
                        } catch (e: Exception) {
                            Logger.error("failed to open channel", e)
                            self.bestAttemptContent?.title = "Channel open failed"
                            self.bestAttemptContent?.body = e.message ?: "Unknown error"
                            self.deliver()
                        }
                    }
                }
            }
            withTimeout(timeout) { deliverSignal.await() } // Stops node on timeout & avoids notification replay by OS
            return Result.success()
        } catch (e: Exception) {
            val reason = e.message ?: "Unknown error"

            self.bestAttemptContent?.title = "Lightning error"
            self.bestAttemptContent?.body = reason
            Logger.error("Lightning error", e)
            self.deliver()

            return Result.failure(workDataOf("Reason" to reason))
        }
    }

    /**
     * Listens for LDK events and delivers the notification if the event matches the notification type.
     * @param event The LDK event to check.
     */
    private suspend fun handleLdkEvent(event: Event) {
        when (event) {
            is Event.PaymentReceived -> {
                bestAttemptContent?.title = "Payment Received"
                val sats = event.amountMsat / 1000u
                // Save for UI to pick up
                NewTransactionSheetDetails.save(
                    appContext,
                    NewTransactionSheetDetails(
                        type = NewTransactionSheetType.LIGHTNING,
                        direction = NewTransactionSheetDirection.RECEIVED,
                        sats = sats.toLong(),
                    )
                )
                bestAttemptContent?.body = "⚡ $sats"
                if (self.notificationType == incomingHtlc) {
                    self.deliver()
                }
            }

            is Event.ChannelPending -> {
                self.bestAttemptContent?.title = "Channel Opened"
                self.bestAttemptContent?.body = "Pending"
                // Don't deliver, give a chance for channelReady event to update the content if it's a turbo channel
            }

            is Event.ChannelReady -> {
                if (self.notificationType == cjitPaymentArrived) {
                    self.bestAttemptContent?.title = "Payment received"
                    self.bestAttemptContent?.body = "Via new channel"

                    lightningRepo.getChannels()?.find { it.channelId == event.channelId }?.let { channel ->
                        val sats = channel.outboundCapacityMsat / 1000u
                        self.bestAttemptContent?.title = "Received ⚡ $sats sats"
                        // Save for UI to pick up
                        NewTransactionSheetDetails.save(
                            appContext,
                            NewTransactionSheetDetails(
                                type = NewTransactionSheetType.LIGHTNING,
                                direction = NewTransactionSheetDirection.RECEIVED,
                                sats = sats.toLong(),
                            )
                        )
                    }
                } else if (self.notificationType == orderPaymentConfirmed) {
                    self.bestAttemptContent?.title = "Channel opened"
                    self.bestAttemptContent?.body = "Ready to send"
                }
                self.deliver()
            }

            is Event.ChannelClosed -> {
                self.bestAttemptContent?.title = "Channel closed"
                self.bestAttemptContent?.body = "Reason: ${event.reason}"

                if (self.notificationType == mutualClose) {
                    self.bestAttemptContent?.body = "Balance moved from spending to savings"
                } else if (self.notificationType == orderPaymentConfirmed) {
                    self.bestAttemptContent?.title = "Channel failed to open in the background"
                    self.bestAttemptContent?.body = "Please try again"
                }

                self.deliver()
            }

            is Event.PaymentSuccessful -> Unit
            is Event.PaymentClaimable -> Unit

            is Event.PaymentFailed -> {
                self.bestAttemptContent?.title = "Payment failed"
                self.bestAttemptContent?.body = "⚡ ${event.reason}"

                if (self.notificationType == wakeToTimeout) {
                    self.deliver()
                }
            }
        }
    }

    private suspend fun deliver() {
        lightningRepo.stop()

        bestAttemptContent?.run {
            pushNotification(title, body, context = appContext)
            Logger.info("Delivered notification")
        }

        deliverSignal.complete(Unit)
    }
}
