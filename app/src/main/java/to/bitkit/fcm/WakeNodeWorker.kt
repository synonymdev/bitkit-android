package to.bitkit.fcm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.lightningdevkit.ldknode.Event
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.env.Tag.LDK
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.blocktank.BlocktankNotificationType
import to.bitkit.models.blocktank.BlocktankNotificationType.cjitPaymentArrived
import to.bitkit.models.blocktank.BlocktankNotificationType.incomingHtlc
import to.bitkit.models.blocktank.BlocktankNotificationType.mutualClose
import to.bitkit.models.blocktank.BlocktankNotificationType.orderPaymentConfirmed
import to.bitkit.models.blocktank.BlocktankNotificationType.wakeToTimeout
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.shared.ServiceError
import to.bitkit.shared.withPerformanceLogging
import to.bitkit.ui.pushNotification
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@HiltWorker
class WakeNodeWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val blocktankService: BlocktankService,
    private val keychain: Keychain,
) : CoroutineWorker(appContext, workerParams) {
    class VisibleNotification(var title: String = "", var body: String = "")

    private var bestAttemptContent: VisibleNotification? = VisibleNotification()

    private var notificationType: BlocktankNotificationType? = null
    private var notificationPayload: JsonObject? = null

    private val self = this

    override suspend fun doWork(): Result {
        Log.d(LDK, "Node wakeup from notification…")

        notificationType = workerParams.inputData.getString("type")?.let { BlocktankNotificationType.valueOf(it) }
        notificationPayload = workerParams.inputData.getString("payload")?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }

        Log.d(LDK, "${this::class.simpleName} notification type: $notificationType")
        Log.d(LDK, "${this::class.simpleName} notification payload: $notificationPayload")

        try {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound
            withPerformanceLogging {
                LightningService.shared.apply {
                    setup(walletIndex = 0, mnemonic)
                    start(timeout = 2.hours) { handleEvent(it) } // stop() is done by deliver() via handleEvent()
                }

                // Once node is started, handle the manual channel opening if needed
                if (self.notificationType == orderPaymentConfirmed) {
                    val orderId = (notificationPayload?.get("orderId") as? JsonPrimitive)?.contentOrNull

                    if (orderId == null) {
                        Log.e(LDK, "Missing orderId")
                    } else {
                        try {
                            blocktankService.openChannel(orderId)
                            Log.i(LDK, "Open channel request for order $orderId")
                        } catch (e: Exception) {
                            Log.e(LDK, "failed to open channel", e)
                            self.bestAttemptContent?.title = "Channel open failed"
                            self.bestAttemptContent?.body = e.message ?: "Unknown error"
                            self.deliver()
                        }
                    }
                }
            }
            return Result.success()
        } catch (e: Exception) {
            val reason = e.message ?: "Unknown error"

            self.bestAttemptContent?.title = "Lightning error"
            self.bestAttemptContent?.body = reason
            Log.e(LDK, "Lightning error", e)
            self.deliver()

            return Result.failure(workDataOf("Reason" to reason))
        }
    }

    /**
     * Listens for LDK events and delivers the notification if the event matches the notification type.
     * @param event The LDK event to check.
     */
    private suspend fun handleEvent(event: Event) {
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

                    LightningService.shared.channels?.firstOrNull { it.channelId == event.channelId }?.let { channel ->
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
                if (self.notificationType == mutualClose) {
                    self.bestAttemptContent?.title = "Channel closed"
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
                self.deliver()

                if (self.notificationType == wakeToTimeout) {
                    self.deliver()
                }
            }
        }
    }

    private suspend fun deliver() {
        delay(30.seconds)
        LightningService.shared.stop()

        bestAttemptContent?.run {
            pushNotification(title, body, context = appContext)
            Log.i(LDK, "Delivered notification")
        }
    }
}
