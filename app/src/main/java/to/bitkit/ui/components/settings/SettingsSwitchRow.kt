package to.bitkit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.shared.modifiers.alphaFeedback
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
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
    colors: SwitchColors = AppSwitchDefaults.colors,
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
    // Null for rows that never load; otherwise the spinner slot stays reserved so the title never reflows.
    isLoading: Boolean? = null,
    subtitle: String? = null,
    switchTestTag: String? = null,
    colors: SwitchColors = AppSwitchDefaults.colors,
) {
    SettingsSwitchRowCore(
        title = title,
        isChecked = isChecked,
        onClick = onClick,
        enabled = enabled,
        isLoading = isLoading,
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
    isLoading: Boolean? = null,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    switchTestTag: String? = null,
    colors: SwitchColors = AppSwitchDefaults.colors,
) {
    val debouncedOnClick = rememberDebouncedClick(onClick = onClick)
    val isInteractive = enabled && isLoading != true
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = (switchTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .alphaFeedback(enabled = isInteractive)
                .toggleable(
                    value = isChecked,
                    enabled = isInteractive,
                    role = Role.Switch,
                    onValueChange = { debouncedOnClick() },
                )
                .semantics { if (isLoading == true) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
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

            if (isLoading != null) {
                Box(modifier = Modifier.size(20.dp)) {
                    if (isLoading) {
                        GradientCircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .matchParentSize()
                                .testTag("${switchTestTag ?: "SettingsSwitchRow"}Loading")
                        )
                    }
                }
                HorizontalSpacer(12.dp)
            }

            Switch(
                checked = isChecked,
                onCheckedChange = null, // handled by parent
                // Keep a loading row's switch at full colour so its target value stays readable.
                enabled = enabled,
                colors = colors,
                modifier = Modifier.clearAndSetSemantics { }
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
                title = "Loading",
                isChecked = true,
                icon = {},
                isLoading = true,
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
