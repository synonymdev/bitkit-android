package to.bitkit.wear.ui.components

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Blocks screenshots, screen recording and the recents preview while the calling screen is shown, as the phone's
 * `BlockScreenshots` does. Unlike the phone it also applies to debug builds, because these screens show the recovery
 * phrase as it is typed.
 */
@Composable
fun BlockScreenshots() {
    val activity = LocalActivity.current ?: return
    val window = activity.window ?: return
    DisposableEffect(window) {
        if (screenshotBlockCounter.enter(window)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            activity.setRecentsScreenshotEnabled(false)
        }
        onDispose {
            if (screenshotBlockCounter.leave(window)) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setRecentsScreenshotEnabled(true)
            }
        }
    }
}

internal val screenshotBlockCounter = ScreenshotBlockCounter()

/** Counts the screens blocking screenshots per window, so the flag is cleared only when the last one leaves. */
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
