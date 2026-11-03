package to.bitkit.ui.shared.effects

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import to.bitkit.env.Env
import java.util.WeakHashMap

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

    DisposableEffect(window) {
        if (screenshotBlockCounter.enter(window)) {
            // Set FLAG_SECURE to block screenshots and screen recording
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

            // For Android 13+ (API 33+), also disable recent screenshots
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(false)
            }
        }

        onDispose {
            if (screenshotBlockCounter.leave(window)) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

                // Re-enable recent screenshots when leaving the screen
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.setRecentsScreenshotEnabled(true)
                }
            }
        }
    }
}

internal val screenshotBlockCounter = ScreenshotBlockCounter()

internal class ScreenshotBlockCounter(
    private val counts: MutableMap<Any, Int> = WeakHashMap(),
) {
    fun enter(key: Any): Boolean {
        val next = (counts[key] ?: 0) + 1
        counts[key] = next
        return next == 1
    }

    fun leave(key: Any): Boolean {
        val current = counts[key] ?: return false
        if (current <= 1) {
            counts.remove(key)
            return true
        }
        counts[key] = current - 1
        return false
    }
}
