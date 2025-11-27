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
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import to.bitkit.App
import to.bitkit.R
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NotificationState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.MainActivity
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import javax.inject.Inject

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
    lateinit var notifyPaymentReceivedHandler: NotifyPaymentReceivedHandler

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
        val command = NotifyPaymentReceived.Command.from(event, includeNotification = true) ?: return

        notifyPaymentReceivedHandler(command).onSuccess { result ->
            if (result !is NotifyPaymentReceived.Result.ShowNotification) return@onSuccess
            if (App.currentActivity?.value != null) return@onSuccess

            showPaymentNotification(result.details, result.notification)
        }
    }

    private fun showPaymentNotification(
        details: NewTransactionSheetDetails,
        notification: NotificationState,
    ) {
        if (App.currentActivity?.value != null) return
        NewTransactionSheetDetails.save(this, details)
        pushNotification(notification.title, notification.body, context = this)
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
