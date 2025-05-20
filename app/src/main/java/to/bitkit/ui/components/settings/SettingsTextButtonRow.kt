package to.bitkit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SettingsTextButtonRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String = "",
    description: String? = null,
    iconRes: Int? = null,
    iconTint: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Column(
            modifier = modifier.then(if (!enabled) Modifier.alpha(0.5f) else Modifier)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickableAlpha(onClick = if (enabled) onClick else null)
            ) {
                if (iconRes != null) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column(
                    verticalArrangement = Arrangement.Center, modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                ) {
                    BodyM(text = title)
                    if (description != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Caption(text = description, color = Colors.White64)
                    }
                }
                BodyM(text = value, color = Colors.White64)
            }
        }
        HorizontalDivider()
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column {
            SettingsTextButtonRow(
                title = "Simple Title Only",
                onClick = {},
            )
            SettingsTextButtonRow(
                title = "Title",
                description = "Description",
                value = "Value",
                onClick = {},
            )
            SettingsTextButtonRow(
                title = "Disabled",
                iconRes = R.drawable.ic_settings,
                enabled = false,
                onClick = {},
            )
        }
    }
}
