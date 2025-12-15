package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.shared.modifiers.alphaFeedback
import to.bitkit.ui.shared.util.primaryButtonStyle
import to.bitkit.ui.theme.AppButtonDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun NumberPadActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Colors.Brand,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
    val height = 28.dp
    val buttonShape = RoundedCornerShape(8.dp)

    if (enabled) {
        Button(
            onClick = onClick,
            colors = AppButtonDefaults.primaryColors.copy(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            ),
            contentPadding = contentPadding,
            shape = buttonShape,
            modifier = modifier
                .requiredHeight(height)
                .primaryButtonStyle(
                    isEnabled = true,
                    shape = buttonShape,
                )
                .alphaFeedback(enabled = enabled)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = buttonShape,
            colors = AppButtonDefaults.secondaryColors,
            contentPadding = contentPadding,
            border = BorderStroke(width = 1.dp, color = color),
            modifier = modifier
                .requiredHeight(height)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
