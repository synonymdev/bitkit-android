package to.bitkit.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import to.bitkit.R
import to.bitkit.ext.notificationManager
import to.bitkit.ext.notificationManagerCompat
import to.bitkit.ext.requiresPermission
import to.bitkit.utils.Logger
import kotlin.random.Random

val Context.CHANNEL_MAIN get() = getString(R.string.app_notifications_channel_id)

fun Context.initNotificationChannel(
    id: String = CHANNEL_MAIN,
    name: String = getString(R.string.app_notifications_channel_name),
    desc: String = getString(R.string.app_notifications_channel_desc),
    importance: Int = NotificationManager.IMPORTANCE_HIGH,
) {
    val channel = NotificationChannel(id, name, importance).apply { description = desc }
    notificationManager.createNotificationChannel(channel)
}

internal fun Context.notificationBuilder(
    extra: Bundle? = null,
    channelId: String = CHANNEL_MAIN,
): NotificationCompat.Builder {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = FLAG_ACTIVITY_CLEAR_TOP
        extra?.let { putExtras(it) }
    }
    val flags = FLAG_IMMUTABLE or FLAG_ONE_SHOT
    // TODO: review if needed:
    val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

    return NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        .setContentIntent(pendingIntent) // fired on tap
        .setAutoCancel(true) // remove on tap
}

internal fun pushNotification(
    title: String?,
    text: String?,
    extras: Bundle? = null,
    bigText: String? = null,
    id: Int = Random.nextInt(),
    context: Context,
): Int {
    Logger.debug("Push notification: $title, $text")

    // Only check permission if running on Android 13+ (SDK 33+)
    val requiresPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.requiresPermission(Manifest.permission.POST_NOTIFICATIONS)

    if (!requiresPermission) {
        val builder = context.notificationBuilder(extras)
            .setContentTitle(title)
            .setContentText(text)
            .apply {
                bigText?.let {
                    setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                }
            }
        context.notificationManagerCompat.notify(id, builder.build())
    }

    return id
}
