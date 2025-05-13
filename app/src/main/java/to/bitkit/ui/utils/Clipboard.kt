package to.bitkit.ui.utils

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import to.bitkit.R

@Composable
fun copyToClipboard(
    text: String,
    label: String = stringResource(R.string.app_name),
    block: (() -> Unit)? = null,
): () -> Unit {
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    return {
        scope.launch {
            val clipData = ClipData.newPlainText(label, text)
            clipboard.setClipEntry(ClipEntry(clipData))
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            block?.invoke()
        }
    }
}
