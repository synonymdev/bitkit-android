package to.bitkit.ui.shared.effects

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import to.bitkit.env.Env

/**
 * Blocks screenshots and screen recording for the current screen.
 * Uses FLAG_SECURE for all Android versions and setRecentsScreenshotEnabled for Android 13+.
 * Only applies in release builds - allows screenshots in debug builds for testing.
 */
@Composable
fun BlockScreenshots() {
    if (Env.isDebug) return

    val activity = LocalActivity.current ?: return
    val window = activity.window ?: return

    DisposableEffect(Unit) {
        // Set FLAG_SECURE to block screenshots and screen recording
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // For Android 13+ (API 33+), also disable recent screenshots
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

            // Re-enable recent screenshots when leaving the screen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(true)
            }
        }
    }
}
