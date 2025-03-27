package to.bitkit.ui.shared.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import to.bitkit.ui.theme.Colors

/**
 * Adjusts the alpha of a composable when it is pressed and makes it clickable.
 * When pressed, the alpha is reduced to provide visual feedback.
 * If `onClick` is null, the clickable behavior is disabled.
 *
 * Analogue of `TouchableOpacity` in React Native.
 */
fun Modifier.clickableAlpha(
    onClick: (() -> Unit)?,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    this
        .graphicsLayer { this.alpha = if (isPressed) 0.7f else 1f }
        .clickable(
            enabled = onClick != null,
            onClick = { onClick?.invoke() },
            interactionSource = interactionSource,
            indication = null,
        )
}


fun Modifier.gradientBackground(): Modifier {
    return Modifier.background(
        brush = Brush.verticalGradient(
            colors = listOf(Colors.Gray7, Colors.Black)
        )
    )
}
