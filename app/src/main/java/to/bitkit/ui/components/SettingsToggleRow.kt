package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SettingsToggleRow(
    label: String,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clickableAlpha { onClick() }
        ) {
            BodyM(text = label, color = Colors.White)

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
            SettingsToggleRow(
                label = "Setting 1",
                isChecked = true,
                onClick = {},
            )
            SettingsToggleRow(
                label = "Setting 2",
                isChecked = false,
                onClick = {},
            )
        }
    }
}
