package to.bitkit.ui.shared.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import to.bitkit.ui.theme.Colors

/**
 * Adjusts the alpha of a composable when it is pressed and makes it clickable.
 * When pressed, the alpha is reduced to provide visual feedback.
 * If `onClick` is null, the clickable behavior is disabled.
 *
 * Analogue of `TouchableOpacity` in React Native.
 */
fun Modifier.clickableAlpha(
    pressedAlpha: Float = 0.7f,
    onClick: (() -> Unit)?,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val wasClicked = remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (!isPressed) {
            wasClicked.value = false
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isPressed || wasClicked.value) pressedAlpha else 1f,
        finishedListener = {
            // Reset the clicked state after animation completes
            wasClicked.value = false
        }
    )

    this
        .graphicsLayer { this.alpha = alpha }
        .clickable(
            enabled = onClick != null,
            onClick = {
                wasClicked.value = true
                onClick?.invoke()
            },
            interactionSource = interactionSource,
            indication = null,
        )
}

fun Modifier.gradientBackground(startColor: Color = Colors.Gray6, endColor: Color = Colors.Black): Modifier {
    return this.background(
        brush = Brush.verticalGradient(
            colors = listOf(startColor, endColor)
        )
    )
}

fun Modifier.blockPointerInputPassthrough(): Modifier {
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent()
            }
        }
    }
}
