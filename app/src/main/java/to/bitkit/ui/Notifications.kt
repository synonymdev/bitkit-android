package to.bitkit.ui

import android.Manifest
import android.Manifest.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import to.bitkit.R
import to.bitkit.ext.notificationManager
import to.bitkit.ext.notificationManagerCompat
import to.bitkit.ext.requiresPermission
import to.bitkit.utils.Logger
import kotlin.random.Random

const val ID_NOTIFICATION_SKIPPED = -1
const val ID_NOTIFICATION_NODE = 1

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

    @Suppress("ForbiddenComment") // TODO: review if needed:
    val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

    return NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_launcher_fg_regtest)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        .setContentIntent(pendingIntent) // fired on tap
        .setAutoCancel(true) // remove on tap
}

internal fun Context.pushNotification(
    title: String?,
    text: String?,
    extras: Bundle? = null,
    bigText: String? = null,
    id: Int = Random.nextInt(),
): Int {
    Logger.debug("Push notification requested: $title, $text", context = TAG)

    // Only check permission if running on Android 13+ (SDK 33+)
    val needsPermissionGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        requiresPermission(permission.POST_NOTIFICATIONS)

    if (!needsPermissionGrant) {
        val builder = notificationBuilder(extras)
            .setContentTitle(title)
            .setContentText(text)
            .apply {
                bigText?.let {
                    setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                }
            }
        notificationManagerCompat.notify(id, builder.build())
        Logger.debug("Push notification posted with id: $id", context = TAG)

        return id
    } else {
        Logger.debug("Push notification skipped: permission not granted", context = TAG)
        return ID_NOTIFICATION_SKIPPED
    }
}

fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
        .onFailure { Logger.error("Failed to open notification settings", e = it, context = TAG) }
}

fun Context.areNotificationsEnabled(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

private const val TAG = "Notifications"
