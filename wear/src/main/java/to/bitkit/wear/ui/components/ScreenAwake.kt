package to.bitkit.wear.ui.components

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Keeps the screen on while the calling screen is shown, and at full brightness when [isBright], as for a QR code
 * that another device scans. Both are undone when the screen leaves.
 */
@Composable
fun ScreenAwake(isBright: Boolean = false) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, isBright) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val brightness = window.attributes.screenBrightness
        if (isBright) {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isBright) window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }
}
