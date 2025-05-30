@file:Suppress("unused")

package to.bitkit.ext

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.ContextWrapper
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import to.bitkit.utils.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

// System Services

val Context.notificationManager: NotificationManager
    get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

val Context.notificationManagerCompat: NotificationManagerCompat
    get() = NotificationManagerCompat.from(this)

val Context.clipboardManager: ClipboardManager
    get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

// Permissions

fun Context.requiresPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) != PERMISSION_GRANTED

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
        Logger.error("Failed to copy asset file: $asset", e)
    }
}

fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

// Clipboard
fun Context.setClipboardText(label: String = "", text: String) {
    this.clipboardManager.setPrimaryClip(
        ClipData.newPlainText(label, text)
    )
}

fun Context.getClipboardText(): String? {
    return this.clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
}
