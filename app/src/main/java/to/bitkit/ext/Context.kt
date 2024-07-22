package to.bitkit.ext

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import to.bitkit.currentActivity
import to.bitkit.ui.MainActivity

// System Services

val Context.notificationManager: NotificationManager
    get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

val Context.notificationManagerCompat: NotificationManagerCompat
    get() = NotificationManagerCompat.from(this)

// Permissions

fun Context.requiresPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) != PERMISSION_GRANTED

// In-App Notifications

internal fun toast(
    text: String,
    duration: Int = Toast.LENGTH_SHORT,
) {
    with(currentActivity<MainActivity>()) {
        Toast.makeText(this, text, duration).show()
    }
}
