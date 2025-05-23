package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.navigateToChangePin
import to.bitkit.ui.navigateToDisablePin
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settings.pin.PinNavigationSheet
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return

    var showPinSheet by remember { mutableStateOf(false) }
    val isPinEnabled by app.isPinEnabled.collectAsStateWithLifecycle()
    val isPinOnLaunchEnabled by app.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinOnIdleEnabled by settings.isPinOnIdleEnabled.collectAsStateWithLifecycle()
    val isPinForPaymentsEnabled by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()

    PinNavigationSheetHost(
        showSheet = showPinSheet,
        onDismiss = { showPinSheet = false },
    ) {
        SecuritySettingsContent(
            isPinEnabled = isPinEnabled,
            isPinOnLaunchEnabled = isPinOnLaunchEnabled,
            isBiometricEnabled = isBiometricEnabled,
            isPinOnIdleEnabled = isPinOnIdleEnabled,
            isPinForPaymentsEnabled = isPinForPaymentsEnabled,
            isBiometrySupported = rememberBiometricAuthSupported(),
            onPinClick = {
                if (!isPinEnabled) {
                    showPinSheet = true
                } else {
                    navController.navigateToDisablePin()
                }
            },
            onChangePinClick = {
                navController.navigateToChangePin()
            },
            onPinOnLaunchClick = {
                navController.navigateToAuthCheck(
                    onSuccessActionId = AuthCheckAction.TOGGLE_PIN_ON_LAUNCH,
                )
            },
            onPinOnIdleClick = {
                navController.navigateToAuthCheck(
                    onSuccessActionId = AuthCheckAction.TOGGLE_PIN_ON_IDLE,
                )
            },
            onPinForPaymentsClick = {
                navController.navigateToAuthCheck(
                    onSuccessActionId = AuthCheckAction.TOGGLE_PIN_FOR_PAYMENTS,
                )
            },
            onUseBiometricsClick = {
                navController.navigateToAuthCheck(
                    requireBiometrics = true,
                    onSuccessActionId = AuthCheckAction.TOGGLE_BIOMETRICS,
                )
            },
            onBackClick = { navController.popBackStack() },
            onCloseClick = { navController.navigateToHome() },
        )
    }
}

@Composable
private fun SecuritySettingsContent(
    isPinEnabled: Boolean,
    isPinOnLaunchEnabled: Boolean,
    isBiometricEnabled: Boolean,
    isPinOnIdleEnabled: Boolean,
    isPinForPaymentsEnabled: Boolean,
    isBiometrySupported: Boolean,
    onPinClick: () -> Unit = {},
    onChangePinClick: () -> Unit = {},
    onPinOnLaunchClick: () -> Unit = {},
    onPinOnIdleClick: () -> Unit = {},
    onPinForPaymentsClick: () -> Unit = {},
    onUseBiometricsClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__security_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__security__pin),
                value = SettingsButtonValue.StringValue(
                    stringResource(
                        if (isPinEnabled) R.string.settings__security__pin_enabled else R.string.settings__security__pin_disabled
                    )
                ),
                onClick = onPinClick,
            )
            if (isPinEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__security__pin_change),
                    onClick = onChangePinClick,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_launch),
                    isChecked = isPinOnLaunchEnabled,
                    onClick = onPinOnLaunchClick,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_idle),
                    isChecked = isPinOnIdleEnabled,
                    onClick = onPinOnIdleClick,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_payments),
                    isChecked = isPinForPaymentsEnabled,
                    onClick = onPinForPaymentsClick,
                )
            }
            if (isPinEnabled && isBiometrySupported) {
                SettingsSwitchRow(
                    title = run {
                        val bioTypeName = stringResource(R.string.security__bio)
                        stringResource(R.string.settings__security__use_bio).replace("{biometryTypeName}", bioTypeName)
                    },
                    isChecked = isBiometricEnabled,
                    onClick = onUseBiometricsClick,
                )
            }
            if (isPinEnabled && isBiometrySupported) {
                BodyS(
                    text = run {
                        val bioTypeName = stringResource(R.string.security__bio)
                        stringResource(R.string.settings__security__footer).replace("{biometryTypeName}", bioTypeName)
                    },
                    color = Colors.White64,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun PinNavigationSheetHost(
    showSheet: Boolean,
    onDismiss: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    SheetHost(
        shouldExpand = showSheet,
        onDismiss = onDismiss,
        sheets = {
            if (showSheet) {
                PinNavigationSheet(showLaterButton = false, onDismiss)
            }
        },
        content = content,
    )
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        SecuritySettingsContent(
            isPinEnabled = true,
            isPinOnLaunchEnabled = true,
            isBiometricEnabled = false,
            isPinOnIdleEnabled = false,
            isPinForPaymentsEnabled = false,
            isBiometrySupported = true,
        )
    }
}
