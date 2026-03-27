package to.bitkit.ui.settings.pin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.sheets.PinRoute
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PinManagementScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return
    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val currentSheet by app.currentSheet.collectAsStateWithLifecycle()
    var pinSheetWasShown by remember { mutableStateOf(false) }

    LaunchedEffect(currentSheet) {
        if (currentSheet is Sheet.Pin || currentSheet is Sheet.ChangePin || currentSheet is Sheet.DisablePin) {
            pinSheetWasShown = true
        } else if (pinSheetWasShown && currentSheet == null) {
            navController.popBackStack()
        }
    }

    Content(
        isPinEnabled = isPinEnabled,
        onEnablePinClick = { app.showSheet(Sheet.Pin(PinRoute.Choose)) },
        onChangePinClick = { app.showSheet(Sheet.ChangePin) },
        onDisablePinClick = { app.showSheet(Sheet.DisablePin) },
        onBackClick = { navController.popBackStack() },
    )
}

@Composable
private fun Content(
    isPinEnabled: Boolean,
    onEnablePinClick: () -> Unit = {},
    onChangePinClick: () -> Unit = {},
    onDisablePinClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(
                if (isPinEnabled) {
                    R.string.security__pin_enabled_title
                } else {
                    R.string.settings__security__pin
                }
            ),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(
                    if (isPinEnabled) {
                        R.string.security__pin_enabled_text
                    } else {
                        R.string.security__pin_security_text
                    }
                ),
                color = Colors.White64,
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = if (isPinEnabled) {
                        painterResource(R.drawable.shield_check)
                    } else {
                        painterResource(R.drawable.shield)
                    },
                    contentDescription = null,
                    modifier = Modifier.size(256.dp)
                )
            }

            if (isPinEnabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.settings__security__pin_change),
                        onClick = onChangePinClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ChangePIN")
                    )
                    PrimaryButton(
                        text = stringResource(R.string.security__pin_disable_button),
                        onClick = onDisablePinClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("DisablePin")
                    )
                }
            } else {
                PrimaryButton(
                    text = stringResource(R.string.security__pin_enable_button),
                    onClick = onEnablePinClick,
                    modifier = Modifier.testTag("EnablePin")
                )
            }

            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewDisabled() {
    AppThemeSurface {
        Content(isPinEnabled = false)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEnabled() {
    AppThemeSurface {
        Content(isPinEnabled = true)
    }
}
