package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppShapes
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
    val borderColor = if (enabled) Color.Transparent else color
    val bgColor = if (enabled) Colors.White10 else Color.Transparent

    Surface(
        color = bgColor,
        shape = AppShapes.small,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clickable(
                enabled = enabled,
                onClick = onClick,
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(PaddingValues(8.dp, 5.dp))
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
