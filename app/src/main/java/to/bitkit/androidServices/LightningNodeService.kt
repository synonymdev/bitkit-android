package to.bitkit.androidServices

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import to.bitkit.App
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.di.UiDispatcher
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NotificationDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.ID_NOTIFICATION_NODE
import to.bitkit.ui.MainActivity
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import to.bitkit.utils.jsonLogOf
import javax.inject.Inject

@AndroidEntryPoint
class LightningNodeService : Service() {

    @Inject
    @UiDispatcher
    lateinit var uiDispatcher: CoroutineDispatcher

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + uiDispatcher) }

    @Inject
    lateinit var lightningRepo: LightningRepo

    @Inject
    lateinit var walletRepo: WalletRepo

    @Inject
    lateinit var notifyPaymentReceivedHandler: NotifyPaymentReceivedHandler

    @Inject
    lateinit var cacheStore: CacheStore

    override fun onCreate() {
        super.onCreate()
        startForeground(ID_NOTIFICATION_NODE, createNotification())
        setupService()
    }

    private fun setupService() {
        serviceScope.launch {
            lightningRepo.start(
                eventHandler = { event ->
                    Logger.debug("LDK-node event received in $TAG: ${jsonLogOf(event)}", context = TAG)
                    handlePaymentReceived(event)
                }
            ).onSuccess {
                walletRepo.setWalletExistsState()
                walletRepo.refreshBip21()
                walletRepo.syncBalances()
            }
        }
    }

    private suspend fun handlePaymentReceived(event: Event) {
        if (event !is Event.PaymentReceived && event !is Event.OnchainTransactionReceived) return
        val command = NotifyPaymentReceived.Command.from(event, includeNotification = true) ?: return

        notifyPaymentReceivedHandler(command).onSuccess {
            Logger.debug("Payment notification result: $it", context = TAG)
            if (it !is NotifyPaymentReceived.Result.ShowNotification) return
            showPaymentNotification(it.sheet, it.notification)
        }
    }

    private fun showPaymentNotification(
        sheet: NewTransactionSheetDetails,
        notification: NotificationDetails,
    ) {
        if (App.currentActivity?.value != null) {
            Logger.debug("Skipping payment notification: activity is active", context = TAG)
            return
        }
        Logger.debug("Showing payment notification: ${notification.title}", context = TAG)
        serviceScope.launch { cacheStore.setBackgroundReceive(sheet) }
        pushNotification(notification.title, notification.body)
    }

    private fun createNotification(
        contentText: String = getString(R.string.notification__service__body),
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // Create stop action that will close both service and app
        val stopIntent = Intent(this, LightningNodeService::class.java).apply {
            action = ACTION_STOP_SERVICE_AND_APP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID_NODE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_bitkit_outlined)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_x,
                getString(R.string.notification__service__stop),
                stopPendingIntent
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.debug("onStartCommand", context = TAG)
        when (intent?.action) {
            ACTION_STOP_SERVICE_AND_APP -> {
                Logger.debug("ACTION_STOP_SERVICE_AND_APP detected", context = TAG)
                // Close activities gracefully without force-stopping the app
                App.currentActivity?.value?.finishAffinity()
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
            lightningRepo.stop()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Logger.warn("Foreground service timeout reached", context = TAG)
        serviceScope.launch {
            lightningRepo.stop()
            stopSelf()
        }
        super.onTimeout(startId, fgsType)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID_NODE = "bitkit_notification_channel_node"
        const val TAG = "LightningNodeService"
        const val ACTION_STOP_SERVICE_AND_APP = "to.bitkit.androidServices.action.STOP_SERVICE_AND_APP"
    }
}
