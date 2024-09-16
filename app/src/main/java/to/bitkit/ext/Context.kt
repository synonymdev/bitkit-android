@file:Suppress("unused")

package to.bitkit.ext

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import to.bitkit.currentActivity
import to.bitkit.env.Tag.APP
import to.bitkit.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

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
    currentActivity<MainActivity>().run {
        Toast.makeText(this, text, duration).show()
    }
}

// File System

fun Context.readAsset(path: String) = assets.open(path).use(InputStream::readBytes)

fun Context.copyAssetToStorage(asset: String, dest: String) {
    val destFile = File(dest)

    try {
        this.assets.open(asset).use { inputStream ->
            FileOutputStream(destFile).use { outputStream ->
                val buffer = ByteArray(1024)
                var length: Int
                while (inputStream.read(buffer).also { length = it } > 0) {
                    outputStream.write(buffer, 0, length)
                }
            }
        }
    } catch (e: IOException) {
        Log.e(APP, "Failed to copy asset file: $asset", e)
    }
}
