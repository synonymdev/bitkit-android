package to.bitkit.ui

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import to.bitkit.R
import to.bitkit.Tag.FCM
import to.bitkit.currentActivity
import to.bitkit.ext.notificationManager
import to.bitkit.ext.notificationManagerCompat
import to.bitkit.ext.requiresPermission
import kotlin.random.Random

val Context.CHANNEL_MAIN get() = getString(R.string.app_notifications_channel_id)

fun Context.initNotificationChannel(
    id: String = CHANNEL_MAIN,
    name: String = getString(R.string.app_notifications_channel_name),
    desc: String = getString(R.string.app_notifications_channel_desc),
    importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
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
    val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

    return NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        .setContentIntent(pendingIntent) // fired on tap
        .setAutoCancel(true) // remove on tap
}

@SuppressLint("MissingPermission")
internal fun pushNotification(
    title: String?,
    text: String?,
    extras: Bundle? = null,
    bigText: String? = null,
    id: Int = Random.nextInt(),
): Int {
    currentActivity<MainActivity>().withPermission(notificationPermission) {
        val builder = notificationBuilder(extras)
            .setContentTitle(title)
            .setContentText(text)
            .apply {
                bigText?.let {
                    setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                }
            }
        notificationManagerCompat.notify(id, builder.build())
    }
    return id
}

inline fun <T> Context.withPermission(permission: String, block: Context.() -> T) {
    if (requiresPermission(permission)) return
    block()
}

val notificationPermission
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        TODO("Cant request 'POST_NOTIFICATIONS' permissions on SDK < 33")
    }

fun logFcmToken() {
    FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
        if (!task.isSuccessful) {
            Log.w(FCM, "FCM registration token error:", task.exception)
            return@OnCompleteListener
        }
        val token = task.result
        // TODO call sharedViewModel.registerForNotifications(token) and move the listener body to there
        Log.d(FCM, "FCM registration token: $token")
    })
}
