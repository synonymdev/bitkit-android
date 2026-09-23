package to.bitkit.ui

import android.Manifest.permission
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
const val EXTRA_PAYKIT_SUBSCRIPTION_PAYMENT_DUE = "paykit_subscription_payment_due"
const val EXTRA_PAYKIT_PAYER_IDENTITY = "paykit_payer_identity"
const val EXTRA_PAYKIT_PAYMENT_REQUEST_ID = "paykit_payment_request_id"
const val EXTRA_PAYKIT_COUNTERPARTY = "paykit_counterparty"
const val EXTRA_PAYKIT_COUNTERPARTY_RECEIVER_PATH = "paykit_counterparty_receiver_path"
const val EXTRA_PAYKIT_BILLING_PERIOD_STARTS_AT = "paykit_billing_period_starts_at"

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
    requestCode: Int = 0,
): NotificationCompat.Builder {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = FLAG_ACTIVITY_CLEAR_TOP
        extra?.let { putExtras(it) }
    }
    val flags = FLAG_IMMUTABLE or FLAG_ONE_SHOT

    val pendingIntent = PendingIntent.getActivity(this, requestCode, intent, flags)

    return NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_bitkit_outlined)
        .setColor(ContextCompat.getColor(this, R.color.brand))
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
        val builder = notificationBuilder(extras, requestCode = id)
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
