package to.bitkit.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp

/** Widest window treated as a phone; illustrations sized as a share of the screen stop growing past it. */
private val PHONE_MAX_WIDTH = 480.dp

/**
 * The window's width capped at a large phone's, for sizes set as a share of the screen,
 * so tablets keep them phone-sized.
 */
@ReadOnlyComposable
@Composable
fun phoneScreenWidth(): Dp = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.width.toDp().coerceAtMost(PHONE_MAX_WIDTH)
}

/** The window's height, for sizes set as a share of the screen. */
@ReadOnlyComposable
@Composable
fun screenHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
