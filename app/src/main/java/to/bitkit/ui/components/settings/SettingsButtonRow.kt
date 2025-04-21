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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SettingsButtonRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    Column(
        modifier = Modifier.height(52.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clickableAlpha { onClick() }
        ) {
            BodyM(text = title, color = Colors.White)

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                value?.let {
                    BodyM(text = it, color = Colors.White)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = Colors.White64,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        HorizontalDivider(color = Colors.White10)
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsButtonRow(
                title = "Setting Button",
                value = "Enabled",
                onClick = {},
            )
            SettingsButtonRow(
                title = "Setting Button Without Value",
                onClick = {},
            )
        }
    }
}
