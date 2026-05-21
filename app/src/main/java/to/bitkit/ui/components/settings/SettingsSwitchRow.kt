package to.bitkit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SettingsSwitchRow(
    title: String,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    iconRes: Int? = null,
    iconTint: Color = Color.Unspecified,
    switchTestTag: String? = null,
    colors: SwitchColors = AppSwitchDefaults.colors
) {
    SettingsSwitchRowCore(
        title = title,
        isChecked = isChecked,
        onClick = onClick,
        enabled = enabled,
        subtitle = subtitle,
        colors = colors,
        switchTestTag = switchTestTag,
        icon = if (iconRes != null) {
            {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(32.dp)
                )
                HorizontalSpacer(10.dp)
            }
        } else {
            null
        },
        modifier = modifier
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    isChecked: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    switchTestTag: String? = null,
    colors: SwitchColors = AppSwitchDefaults.colors
) {
    SettingsSwitchRowCore(
        title = title,
        isChecked = isChecked,
        onClick = onClick,
        enabled = enabled,
        subtitle = subtitle,
        colors = colors,
        switchTestTag = switchTestTag,
        icon = {
            icon()
            HorizontalSpacer(8.dp)
        },
        modifier = modifier
    )
}

@Composable
private fun SettingsSwitchRowCore(
    title: String,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    switchTestTag: String? = null,
    colors: SwitchColors = AppSwitchDefaults.colors
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickableAlpha(enabled = enabled) { onClick() }
        ) {
            if (icon != null) {
                icon()
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BodyM(text = title, color = Colors.White, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    BodyS(text = subtitle, color = Colors.White64)
                }
            }

            Switch(
                checked = isChecked,
                onCheckedChange = null, // handled by parent
                enabled = enabled,
                colors = colors,
                modifier = switchTestTag?.let { Modifier.testTag(it) } ?: Modifier
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
                onClick = {}
            )
            SettingsSwitchRow(
                title = "Setting 2",
                isChecked = false,
                onClick = {},
            )
            SettingsSwitchRow(
                title = "With Icon",
                isChecked = true,
                iconRes = R.drawable.ic_eye,
                onClick = {},
            )
        }
    }
}
