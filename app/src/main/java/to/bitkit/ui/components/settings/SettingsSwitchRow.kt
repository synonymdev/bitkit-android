package to.bitkit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SettingsSwitchRow(
    title: String,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.height(52.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickableAlpha { onClick() }
                .padding(vertical = 16.dp)
        ) {
            BodyM(text = title, color = Colors.White)

            Switch(
                checked = isChecked,
                onCheckedChange = null, // handled by parent
                colors = AppSwitchDefaults.colors,
            )
        }
        HorizontalDivider(color = Colors.White10)
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsSwitchRow(
                title = "Setting 1",
                isChecked = true,
                onClick = {},
            )
            SettingsSwitchRow(
                title = "Setting 2",
                isChecked = false,
                onClick = {},
            )
        }
    }
}
