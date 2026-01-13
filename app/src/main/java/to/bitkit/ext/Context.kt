package to.bitkit.ext

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import to.bitkit.R
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

// Clipboard
fun Context.setClipboardText(text: String, label: String = getString(R.string.app_name)) {
    this.clipboardManager.setPrimaryClip(
        ClipData.newPlainText(label, text)
    )
}

fun Context.getClipboardText(): String? {
    return this.clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
}

// Other

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Context.startActivityAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        this.data = "package:$packageName".toUri()
    }

    if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
    } else {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
