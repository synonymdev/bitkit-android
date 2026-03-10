package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.shared.modifiers.alphaFeedback
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes

@Composable
fun RectangleButton(
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    imageVector: ImageVector? = null,
    iconTint: Color = Colors.White,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    onClick: () -> Unit = {},
) {
    Button(
        onClick = rememberDebouncedClick(onClick = onClick),
        colors = ButtonDefaults.buttonColors(
            containerColor = Colors.Gray6,
        ),
        enabled = enabled,
        shape = Shapes.medium,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .alphaFeedback(enabled = enabled)
            .height(80.dp)
            .fillMaxWidth()
    ) {
        icon?.let {
            CircularIcon(
                painter = painterResource(it),
                iconTint = iconTint,
                iconSize = iconSize
            )
        }
        imageVector?.let {
            CircularIcon(
                imageVector = it,
                iconTint = iconTint,
                iconSize = iconSize
            )
        }
        HorizontalSpacer(16.dp)
        BodyMSB(text = label, color = Colors.White)
        FillWidth()
    }
}

@Composable
private fun CircularIcon(
    iconTint: Color,
    iconSize: Dp,
    painter: Painter? = null,
    imageVector: ImageVector? = null,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .size(40.dp)
            .background(Colors.Black),
        contentAlignment = Alignment.Center
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(iconSize),
            )

            imageVector != null -> Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Preview
@Composable
private fun RectangleButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            RectangleButton(
                label = "Button",
                icon = R.drawable.ic_scan
            )
            RectangleButton(
                label = "Button Disabled",
                enabled = false,
                icon = null,
                iconTint = Colors.Purple,
                imageVector = Icons.Default.ContentPaste,
            )
        }
    }
}
