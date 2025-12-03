package to.bitkit.ui.shared.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    onClick = {
                        wasClicked.value = true
                        onClick()
                    },
                    interactionSource = interactionSource,
                    indication = null,
                )
            } else {
                Modifier
            }
        )
}

fun Modifier.gradientBackground(startColor: Color = Colors.Gray6, endColor: Color = Colors.Black): Modifier {
    return this.background(
        brush = Brush.verticalGradient(
            colors = listOf(startColor, endColor)
        )
    )
}

/**
 * Draws an inner highlight at the top edge to create depth/volume effect.
 * Matches iOS shadow: .shadow(color: shadowColor, radius: 0, x: 0, y: -1)
 *
 * @param color The highlight color (typically white with alpha)
 * @param blurRadius The blur radius for soft edges (use 0.dp for sharp, 2-4.dp for smooth)
 * @param shape The shape to clip the highlight to (must match parent shape)
 */
fun Modifier.innerShadow(
    color: Color,
    blurRadius: Dp = 4.dp,
    shape: Shape,
): Modifier = this.drawWithContent {
    // Draw content first (gradient, etc)
    drawContent()

    // Convert blur radius to pixels using DrawScope's density
    val blurRadiusPx = blurRadius.toPx()

    // Get shape outline for clipping
    val outline = shape.createOutline(size, layoutDirection, this)

    // Convert outline to path
    val path = when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }

    // Clip to shape and draw top edge highlight with soft gradient
    clipPath(path) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    color,
                    color.copy(alpha = color.alpha * 0.7f),
                    color.copy(alpha = color.alpha * 0.3f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = blurRadiusPx * 2.5f
            ),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, blurRadiusPx * 2.5f)
        )
    }
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

@Composable
fun Modifier.screen(
    noBackground: Boolean = false,
    insets: WindowInsets? = WindowInsets.systemBars,
): Modifier = this
    .fillMaxSize()
    .then(if (noBackground) Modifier else Modifier.background(MaterialTheme.colorScheme.background))
    .then(if (insets == null) Modifier else Modifier.windowInsetsPadding(insets))
