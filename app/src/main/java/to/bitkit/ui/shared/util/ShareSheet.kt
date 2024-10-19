package to.bitkit.ui.shared.util

import android.content.Context
import android.content.Intent

fun shareText(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, uri)
    }
    val chooser = Intent.createChooser(intent, "Share via")
    context.startActivity(chooser)
}
