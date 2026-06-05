package to.bitkit.appwidget

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import to.bitkit.R
import to.bitkit.ui.ID_NOTIFICATION_NODE
import to.bitkit.ui.initNotificationChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppWidgetRefreshForeground @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun foregroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        context.initNotificationChannel(
            id = CHANNEL_ID,
            name = context.getString(R.string.notification__channel_node__name),
            desc = context.getString(R.string.notification__channel_node__body),
            importance = NotificationManager.IMPORTANCE_LOW,
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.appwidget__loading))
            .setSmallIcon(R.drawable.ic_bitkit_outlined)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private companion object {
        private const val CHANNEL_ID = "bitkit_notification_channel_widget_refresh"
        private const val NOTIFICATION_ID = ID_NOTIFICATION_NODE + 1
    }
}

internal fun shouldPromoteWidgetRefreshToForeground(reason: String, hasRemoteTypes: Boolean): Boolean =
    hasRemoteTypes && reason != AppWidgetRefreshReason.APP_FOREGROUND.name
