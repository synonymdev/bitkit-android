package to.bitkit.androidServices

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import to.bitkit.App
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.MainActivity
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@AndroidEntryPoint
class LightningNodeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Inject
    lateinit var lightningRepo: LightningRepo

    @Inject
    lateinit var walletRepo: WalletRepo

    @Inject
    lateinit var ldkNodeEventBus: LdkNodeEventBus

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var activityRepo: ActivityRepo

    @Inject
    lateinit var currencyRepo: CurrencyRepo

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        setupService()
    }

    private fun setupService() {
        serviceScope.launch {
            launch {
                lightningRepo.start(
                    eventHandler = { event ->
                        walletRepo.refreshBip21ForEvent(event)
                    }
                ).onSuccess {
                    val notification = createNotification()
                    startForeground(NOTIFICATION_ID, notification)

                    walletRepo.setWalletExistsState()
                    walletRepo.refreshBip21()
                    walletRepo.syncBalances()
                }
            }

            launch {
                ldkNodeEventBus.events.collect { event ->
                    handleBackgroundEvent(event)
                }
            }
        }
    }

    private suspend fun handleBackgroundEvent(event: Event) {
        delay(0.5.seconds) // Small delay to allow lifecycle callbacks to settle after app backgrounding
        if (App.currentActivity?.value != null) return

        when (event) {
            is Event.PaymentReceived -> {
                val sats = event.amountMsat / 1000u
                showPaymentNotification(sats.toLong(), event.paymentHash, isOnchain = false)
            }

            is Event.OnchainTransactionReceived -> {
                val sats = event.details.amountSats
                val shouldShow = activityRepo.shouldShowPaymentReceived(event.txid, sats.toULong())
                if (!shouldShow) return

                showPaymentNotification(sats, event.txid, isOnchain = true)
            }

            else -> Unit
        }
    }

    private suspend fun showPaymentNotification(sats: Long, paymentHashOrTxId: String?, isOnchain: Boolean) {
        if (App.currentActivity?.value != null) return

        val settings = settingsStore.data.first()
        val type = if (isOnchain) NewTransactionSheetType.ONCHAIN else NewTransactionSheetType.LIGHTNING
        val direction = NewTransactionSheetDirection.RECEIVED

        NewTransactionSheetDetails.save(
            this,
            NewTransactionSheetDetails(type, direction, paymentHashOrTxId, sats)
        )

        val title = getString(R.string.notification_received_title)
        val body = if (settings.showNotificationDetails) {
            formatNotificationAmount(sats, settings)
        } else {
            getString(R.string.notification_received_body_hidden)
        }

        pushNotification(title, body, context = this)
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

        return getString(R.string.notification_received_body_amount, amountText)
    }

    private fun createNotification(
        contentText: String = getString(R.string.notification_running_in_background),
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Create stop action that will close both service and app
        val stopIntent = Intent(this, LightningNodeService::class.java).apply {
            action = ACTION_STOP_SERVICE_AND_APP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_NODE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_fg_regtest)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_x,
                getString(R.string.notification_stop_app),
                stopPendingIntent
            )
            .build()
    }

    // Update the onStartCommand method
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.debug("onStartCommand", context = TAG)
        when (intent?.action) {
            ACTION_STOP_SERVICE_AND_APP -> {
                Logger.debug("ACTION_STOP_SERVICE_AND_APP detected", context = TAG)
                // Close all activities
                App.currentActivity?.value?.finishAndRemoveTask()
                // Stop the service
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.debug("onDestroy", context = TAG)
        serviceScope.launch {
            lightningRepo.stop().onSuccess {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        const val CHANNEL_ID_NODE = "bitkit_notification_channel_node"
        const val TAG = "LightningNodeService"
        const val ACTION_STOP_SERVICE_AND_APP = "to.bitkit.androidServices.action.STOP_SERVICE_AND_APP"
    }
}
