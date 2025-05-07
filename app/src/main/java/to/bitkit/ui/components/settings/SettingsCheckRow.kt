package to.bitkit.ui.components.settings

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
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SettingsCheckRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isOn: Boolean,
    onClick: () -> Unit,
    iconRes: Int? = null,
    iconTint: Color = Color.Unspecified,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clickableAlpha { onClick() }
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
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                if (subtitle != null) {
                    BodyMSB(text = title)
                    Spacer(modifier = Modifier.height(4.dp))
                    BodySSB(
                        text = subtitle,
                        color = Colors.White64,
                    )
                } else {
                    BodyM(text = title)
                }
            }
            if (isOn) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(32.dp),
                )
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
            SettingsCheckRow(
                title = "Selected",
                subtitle = "Subtitle for selected",
                isOn = true,
                onClick = {},
                iconRes = R.drawable.ic_speed_fast,
            )
            SettingsCheckRow(
                title = "Not Selected",
                subtitle = "Subtitle of item",
                isOn = false,
                onClick = {},
                iconRes = R.drawable.ic_settings,
                iconTint = Colors.White,
            )
            SettingsCheckRow(
                title = "No Icon or subtitle",
                isOn = false,
                onClick = {},
            )
        }
    }
}
