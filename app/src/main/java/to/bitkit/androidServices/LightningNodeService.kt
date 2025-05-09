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
import to.bitkit.App
import to.bitkit.R
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.MainActivity
import to.bitkit.utils.Logger
import javax.inject.Inject

@AndroidEntryPoint
class LightningNodeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Inject
    lateinit var lightningRepo: LightningRepo

    @Inject
    lateinit var walletRepo: WalletRepo

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
        }
    }

    // Update the createNotification method in LightningNodeService.kt
    private fun createNotification(
        contentText: String = "Bitkit is running in background so you can receive Lightning payments"
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Create stop action that will close both service and app
        val stopIntent = Intent(this, LightningNodeService::class.java).apply {
            action = ACTION_STOP_SERVICE_AND_APP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_NODE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_fg_regtest)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_x,
                "Stop App", // TODO: Get from resources
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
