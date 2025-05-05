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
import to.bitkit.R
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.MainActivity
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
        setupService()
    }

    private fun setupService() {
        serviceScope.launch {
            startForeground(NOTIFICATION_ID, createNotification())

            launch {
                lightningRepo.start(
                    eventHandler = { event ->
                        walletRepo.refreshBip21ForEvent(event)
                    }
                ).onSuccess {
                    val notification = createNotification()
                    startForeground(NOTIFICATION_ID, notification)

                    walletRepo.registerForNotifications()
                    walletRepo.refreshBip21()
                }
            }
        }
    }

    private fun createNotification(
        contentText: String = "Bitkit is running in background so you can receive Lightning payments"
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BITKIT_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
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
        const val BITKIT_CHANNEL_ID = "bitkit_notification_channel"
    }
}
