package to.bitkit.ui.shared.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.io.IOException
import java.io.File
import java.io.FileOutputStream

fun shareText(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, uri)
    }
    val chooser = Intent.createChooser(intent, "Share via")
    context.startActivity(chooser)
}

fun shareQrCode(context: Context, bitmap: Bitmap, text: String) {
    try {
        // Create a temporary file to store the QR code image
        val cachePath = File(context.cacheDir, "qr_codes")
        cachePath.mkdirs()

        val imageFile = File(cachePath, "qr_code_${System.currentTimeMillis()}.png")
        val fileOutputStream = FileOutputStream(imageFile)

        // Compress and save the bitmap
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
        fileOutputStream.close()

        // Get URI for the file
        val imageUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )

        // Create sharing intent
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Share Qr code via")
        context.startActivity(chooser)

    } catch (e: IOException) {
        e.printStackTrace()
        // Fallback to text-only sharing
        shareText(context, text)
    }
}
