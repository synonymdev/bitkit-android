package to.bitkit.ui.shared.modifiers

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.theme.Insets
import to.bitkit.ui.theme.TopBarHeight

fun Modifier.sheetHeight(
    size: SheetSize = SheetSize.LARGE,
    isModal: Boolean = false,
): Modifier = composed {
    // Bottom safe-area belongs in sheet content padding; including it here moves
    // non-modal sheet tops down on devices with larger navigation-bar insets.
    val modalBottomPadding = if (isModal) Insets.Bottom + Insets.Bottom else 0.dp
    val topPadding = Insets.Top + modalBottomPadding + TopBarHeight - 6.dp

    val height = when (size) {
        SheetSize.LARGE -> screenHeight(minus = topPadding) // topbar visible

        SheetSize.MEDIUM -> {
            // topbar + balance visible
            val preferred = screenHeight(minus = topPadding + 116.dp)
            val max = screenHeight(minus = topPadding)
            val min = minOf(600.dp, max)
            maxOf(preferred, min)
        }

        SheetSize.CALENDAR -> {
            // searchbar visible
            val preferred = screenHeight(minus = topPadding + 76.dp)
            val max = screenHeight(minus = topPadding)
            val min = minOf(600.dp, max)
            maxOf(preferred, min)
        }

        SheetSize.SMALL -> 400.dp + Insets.Bottom
    }

    return@composed this.then(
        Modifier.height(height)
    )
}

@ReadOnlyComposable
@Composable
private fun screenHeight(
    minus: Dp,
): Dp {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val height = with(density) { windowSize.height.toDp() - minus }.coerceAtLeast(0.dp)
    return height
}
