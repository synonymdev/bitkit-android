package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
import to.bitkit.ui.theme.Colors

@Composable
fun ActionButton(
    onClick: () -> Unit,
    @DrawableRes iconRes: Int? = null,
    imageVector: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = rememberDebouncedClick(onClick = onClick),
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(listOf(Colors.Gray5, Colors.Gray6)),
                CircleShape,
            )
            .border(1.dp, Colors.White10, CircleShape)
    ) {
        val tint = if (enabled) Colors.White else Colors.White32
        when {
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
