package to.bitkit.ui.shared.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors

fun Modifier.gradientBackground(
    startColor: Color = Colors.White08,
    endColor: Color = Color.White.copy(alpha = 0.012f),
): Modifier {
    return this
        .background(Color.Black)
        .background(
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

@Composable
fun Modifier.screen(
    noBackground: Boolean = false,
    insets: WindowInsets? = WindowInsets.systemBars,
): Modifier = this
    .fillMaxSize()
    .then(if (noBackground) Modifier else Modifier.background(MaterialTheme.colorScheme.background))
    .then(if (insets == null) Modifier else Modifier.windowInsetsPadding(insets))

/**
 * Draws an animated outer glow effect that extends beyond the component's bounds.
 * Uses Canvas with setShadowLayer to create a blur effect.
 *
 * @param glowColor The color of the glow effect
 * @param glowOpacity The animated opacity value (0.0 to 1.0)
 * @param glowRadius The blur radius in dp (how far the glow extends)
 * @param cornerRadius The corner radius of the glow shape in dp
 */
fun Modifier.outerGlow(
    glowColor: Color,
    glowOpacity: Float,
    glowRadius: Dp = 12.dp,
    cornerRadius: Dp = 16.dp,
): Modifier = this.then(
    OuterGlowElement(
        glowColor = glowColor,
        glowOpacity = glowOpacity,
        glowRadius = glowRadius,
        cornerRadius = cornerRadius
    )
)

private data class OuterGlowElement(
    val glowColor: Color,
    val glowOpacity: Float,
    val glowRadius: Dp,
    val cornerRadius: Dp,
) : ModifierNodeElement<OuterGlowNode>() {
    override fun create(): OuterGlowNode = OuterGlowNode(
        glowColor = glowColor,
        glowOpacity = glowOpacity,
        glowRadius = glowRadius,
        cornerRadius = cornerRadius
    )

    override fun update(node: OuterGlowNode) {
        node.glowColor = glowColor
        node.glowOpacity = glowOpacity
        node.glowRadius = glowRadius
        node.cornerRadius = cornerRadius
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "outerGlow"
        properties["glowColor"] = glowColor
        properties["glowOpacity"] = glowOpacity
        properties["glowRadius"] = glowRadius
        properties["cornerRadius"] = cornerRadius
    }
}

private class OuterGlowNode(
    var glowColor: Color,
    var glowOpacity: Float,
    var glowRadius: Dp,
    var cornerRadius: Dp,
) : DrawModifierNode, Modifier.Node() {
    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val glowRadiusPx = glowRadius.toPx()
        val cornerRadiusPx = cornerRadius.toPx()

        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = glowColor.copy(alpha = 0f) // Transparent fill
                isAntiAlias = true
            }

            // Draw blurred shadow behind the component
            val frameworkPaint = paint.asFrameworkPaint()
            frameworkPaint.color = glowColor.copy(alpha = 0f).toArgb()
            frameworkPaint.setShadowLayer(
                glowRadiusPx,
                0f,
                0f,
                glowColor.copy(alpha = glowOpacity).toArgb()
            )

            canvas.drawRoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = cornerRadiusPx,
                radiusY = cornerRadiusPx,
                paint = paint
            )
        }

        // Draw the actual content
        drawContent()
    }
}

fun Modifier.primaryButtonStyle(
    isEnabled: Boolean,
    shape: Shape,
    primaryColor: Color? = null,
): Modifier {
    return this
        // Step 1: Add shadow (only when enabled)
        .then(
            if (isEnabled) {
                Modifier.shadow(
                    elevation = 16.dp,
                    shape = shape,
                    clip = false // Don't clip content, just add shadow
                )
            } else {
                Modifier
            }
        )
        // Step 2: Clip to shape first
        .clip(shape)
        // Step 3: Apply gradient background with border overlay
        .then(
            if (isEnabled) {
                Modifier.drawWithContent {
                    // Draw the main gradient background filling entire button
                    val mainBrush = Brush.verticalGradient(
                        colors = listOf(primaryColor ?: Colors.Gray5, Colors.Gray6),
                        startY = 0f,
                        endY = size.height
                    )
                    drawRect(
                        brush = mainBrush,
                        topLeft = Offset.Zero,
                        size = size
                    )

                    // Draw top border highlight (2dp gradient fade)
                    val borderHeight = 2.dp.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Colors.White16,
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = borderHeight
                        ),
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, borderHeight)
                    )

                    // Draw the actual button content on top
                    drawContent()
                }
            } else {
                Modifier.background(Colors.White06)
            }
        )
}
