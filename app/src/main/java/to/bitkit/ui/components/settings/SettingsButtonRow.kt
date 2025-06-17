package to.bitkit.ui.components.settings

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

sealed class SettingsButtonValue {
    data class BooleanValue(val checked: Boolean) : SettingsButtonValue()
    data class StringValue(val value: String) : SettingsButtonValue()
    data object None : SettingsButtonValue()
}

@Composable
fun SettingsButtonRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: SettingsButtonValue = SettingsButtonValue.None,
    description: String? = null,
    iconRes: Int? = null,
    iconTint: Color = Color.Unspecified,
    iconSize: Dp = 32.dp,
    maxLinesSubtitle: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val alphaModifier = Modifier.then(if (!enabled) Modifier.alpha(0.5f) else Modifier)
    Column(
        modifier = modifier
            .clickableAlpha(onClick = if (enabled) onClick else null)
    ) {
        Column(modifier = alphaModifier) {
            val rowHeight = when {
                subtitle != null && iconRes != null -> 90.dp
                subtitle != null -> 74.dp
                else -> 52.dp
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = rowHeight)
            ) {
                if (iconRes != null) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(iconSize),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                ) {
                    if (subtitle != null) {
                        BodyMSB(text = title)
                        Spacer(modifier = Modifier.height(4.dp))
                        BodySSB(
                            text = subtitle,
                            maxLines = maxLinesSubtitle,
                            color = Colors.White64,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        BodyM(text = title)
                    }
                }

                when (value) {
                    is SettingsButtonValue.BooleanValue -> {
                        Crossfade(targetState = loading to value.checked) { (isLoading, isChecked) ->
                            when {
                                isLoading && isChecked -> CircularProgressIndicator(
                                    color = Colors.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(32.dp),
                                )

                                isChecked -> Icon(
                                    painter = painterResource(R.drawable.ic_checkmark),
                                    contentDescription = null,
                                    tint = Colors.Brand,
                                    modifier = Modifier.size(32.dp),
                                )

                                else -> Unit
                            }
                        }
                    }

                    is SettingsButtonValue.StringValue -> {
                        BodyM(text = value.value)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = Colors.White64,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    SettingsButtonValue.None -> {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = Colors.White64,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        if (description != null) {
            BodyS(
                text = description,
                color = Colors.White64,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .then(alphaModifier),
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column {
            SettingsButtonRow(
                title = "Selected",
                subtitle = "Subtitle for selected",
                value = SettingsButtonValue.BooleanValue(true),
                iconRes = R.drawable.ic_speed_fast,
                iconTint = Colors.Brand,
                onClick = {},
            )
            SettingsButtonRow(
                title = "Not Selected",
                subtitle = "Subtitle of item",
                value = SettingsButtonValue.BooleanValue(false),
                iconRes = R.drawable.ic_speed_normal,
                iconTint = Colors.Brand,
                onClick = {},
            )
            SettingsButtonRow(
                title = "String Value",
                value = SettingsButtonValue.StringValue("USD"),
                iconRes = R.drawable.ic_settings,
                iconTint = Colors.White,
                onClick = {},
            )
            SettingsButtonRow(
                title = "No Value",
                iconRes = R.drawable.ic_users,
                onClick = {},
            )
            SettingsButtonRow(
                title = "Loading",
                iconRes = R.drawable.ic_copy,
                loading = true,
                value = SettingsButtonValue.BooleanValue(true),
                onClick = {},
            )
            SettingsButtonRow(
                title = "Item With Description",
                description = "This is a long description text that should normally overflow when rendered in preview.",
                onClick = {},
            )
            SettingsButtonRow(
                title = "Disabled With Description",
                enabled = false,
                description = "This is a description.",
                onClick = {},
            )
        }
    }
}
