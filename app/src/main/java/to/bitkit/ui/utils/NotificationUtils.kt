package to.bitkit.ui.utils

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import to.bitkit.utils.Logger

object NotificationUtils {
    /**
     * Opens the Android system notification settings for the app.
     * On Android 8.0+ (API 26+), opens the app's notification settings.
     * On older versions, opens the general app settings.
     */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Logger.error("Failed to open notification settings", e = it, context = "NotificationUtils")
        }
    }

    /**
     * Checks if notification permissions are granted.
     * For Android 13+ (API 33+), checks the POST_NOTIFICATIONS permission.
     * For older versions, checks if notifications are enabled via NotificationManagerCompat.
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
