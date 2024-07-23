package to.bitkit.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import to.bitkit.R
import to.bitkit._FCM
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
    channelId: String = CHANNEL_MAIN,
): NotificationCompat.Builder {
    val activityIntent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, FLAG_IMMUTABLE)

    return NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent) // fired on tap
        .setAutoCancel(true) // remove on tap
}

internal fun pushNotification(
    title: String,
    text: String,
    bigText: String,
): Int {
    val id = Random.nextInt()
    with(currentActivity<MainActivity>()) {
        pushNotification(
            title = title,
            text = text,
            bigText = bigText,
            id = id,
        )
    }
    return id
}

@SuppressLint("MissingPermission") // Handled by custom guard
internal fun Activity.pushNotification(
    title: String,
    text: String,
    bigText: String,
    id: Int,
) {
    if (requiresPermission(Manifest.permission.POST_NOTIFICATIONS)) return

    val builder = notificationBuilder()
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))

    notificationManagerCompat.notify(id, builder.build())
}

fun logFcmToken() {
    FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
        if (!task.isSuccessful) {
            Log.w(_FCM, "FCM registration token error:\n", task.exception)
            return@OnCompleteListener
        }
        val token = task.result
        Log.d(_FCM, "FCM registration token: $token")
    })
}
