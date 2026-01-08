package to.bitkit.ui.shared.effects

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import to.bitkit.env.Env

/**
 * Blocks screenshots and screen recording for the current screen by setting FLAG_SECURE.
 * Only applies in release builds - allows screenshots in debug builds for testing.
 */
@Composable
fun BlockScreenshots() {
    if (Env.isDebug) return

    val window = LocalActivity.current?.window ?: return

    DisposableEffect(Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
