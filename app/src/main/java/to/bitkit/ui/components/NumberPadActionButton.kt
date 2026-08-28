package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.primaryButtonStyle
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun NumberPadActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Colors.Brand,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    val contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
    val height = 28.dp
    val buttonShape = RoundedCornerShape(8.dp)

    if (enabled || isLoading) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier
                .requiredHeight(height)
                .primaryButtonStyle(
                    isEnabled = enabled || isLoading,
                    shape = buttonShape,
                )
                .animateContentSize(animationSpec = tween(durationMillis = 200))
                .clickableAlpha(enabled = enabled && !isLoading, onClick = onClick)
                .padding(contentPadding)
        ) {
            if (isLoading) {
                GradientCircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            } else if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = text,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Caption13Up(
                text = text,
                color = color,
            )
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier
                .requiredHeight(height)
                .border(
                    width = 1.dp,
                    color = color,
                    shape = buttonShape
                )
                .padding(contentPadding)
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = text,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Caption13Up(
                text = text,
                color = color,
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            NumberPadActionButton(
                text = "Normal",
                onClick = {},
            )
            NumberPadActionButton(
                text = "Disabled",
                enabled = false,
                onClick = {},
            )
            NumberPadActionButton(
                text = "Icon",
                color = Colors.Purple,
                icon = R.drawable.ic_transfer,
                onClick = {},
            )
        }
    }
}
