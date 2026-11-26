package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
import to.bitkit.ui.theme.Colors

@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    imageVector: ImageVector? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    IconButton(
        onClick = rememberDebouncedClick(onClick = onClick),
        enabled = enabled && !isLoading,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(listOf(Colors.Gray5, Colors.Gray6)),
                CircleShape,
            )
            .topInnerHighlight()
    ) {
        val tint = if (enabled) Colors.White else Colors.White32
        when {
            isLoading -> GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
            iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            imageVector != null -> Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Draws Figma's 1px top-only inside stroke: the crescent between the circle and the same circle
 * shifted down by the stroke width, so the edge is 1dp at the top and fades out towards the sides.
 */
private fun Modifier.topInnerHighlight(): Modifier = drawWithCache {
    val strokeWidth = 1.dp.toPx()
    val outer = Path().apply { addOval(Rect(Offset.Zero, size)) }
    val inner = Path().apply { addOval(Rect(Offset(0f, strokeWidth), size)) }
    val crescent = Path().apply { op(outer, inner, PathOperation.Difference) }
    onDrawBehind { drawPath(crescent, Colors.White10) }
}
