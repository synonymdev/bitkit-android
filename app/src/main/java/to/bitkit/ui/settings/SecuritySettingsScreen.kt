package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.filterNotNull
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.navigateToDisablePin
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settings.pin.PinNavigationSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
    savedStateHandle: SavedStateHandle,
) {
    val app = appViewModel ?: return

    var showPinSheet by remember { mutableStateOf(false) }
    val isPinEnabled by app.isPinEnabled.collectAsStateWithLifecycle()
    val isPinOnLaunchEnabled by app.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(savedStateHandle) {
        savedStateHandle.getStateFlow<String?>(AuthCheckAction.KEY, null)
            .filterNotNull()
            .collect { actionId ->
                when (actionId) {
                    AuthCheckAction.Id.TOGGLE_BIOMETRICS -> {
                        app.setIsBiometricEnabled(!isBiometricEnabled)
                    }

                    AuthCheckAction.Id.TOGGLE_PIN_ON_LAUNCH -> {
                        app.setIsPinOnLaunchEnabled(!isPinOnLaunchEnabled)
                    }
                }
                savedStateHandle.remove<String>(AuthCheckAction.KEY)
            }
    }

    PinNavigationSheet(
        showSheet = showPinSheet,
        showLaterButton = false,
        onDismiss = { showPinSheet = false },
    ) {
        SecuritySettingsContent(
            isPinEnabled = isPinEnabled,
            isPinOnLaunchEnabled = isPinOnLaunchEnabled,
            isBiometricEnabled = isBiometricEnabled,
            isBiometrySupported = rememberBiometricAuthSupported(),
            onPinClick = {
                if (!isPinEnabled) {
                    showPinSheet = true
                } else {
                    navController.navigateToDisablePin()
                }
            },
            onPinOnLaunchClick = {
                navController.navigateToAuthCheck(
                    onSuccessActionId = AuthCheckAction.Id.TOGGLE_PIN_ON_LAUNCH,
                )
            },
            onUseBiometricsClick = {
                navController.navigateToAuthCheck(
                    requireBiometrics = true,
                    onSuccessActionId = AuthCheckAction.Id.TOGGLE_BIOMETRICS,
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
    isBiometrySupported: Boolean,
    onPinClick: () -> Unit = {},
    onPinOnLaunchClick: () -> Unit = {},
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
                value = stringResource(
                    if (isPinEnabled) R.string.settings__security__pin_enabled else R.string.settings__security__pin_disabled
                ),
                onClick = onPinClick,
            )
            if (isPinEnabled) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_launch),
                    isChecked = isPinOnLaunchEnabled,
                    onClick = onPinOnLaunchClick,
                )
            }
            if (isPinEnabled && isBiometrySupported) {
                SettingsSwitchRow(
                    title = let {
                        val bioTypeName = stringResource(R.string.security__bio)
                        stringResource(R.string.settings__security__use_bio).replace("{biometryTypeName}", bioTypeName)
                    },
                    isChecked = isBiometricEnabled,
                    onClick = onUseBiometricsClick,
                )
            }
            if (isPinEnabled && isBiometrySupported) {
                BodyS(
                    text = let {
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

@Preview
@Composable
fun Preview() {
    AppThemeSurface {
        SecuritySettingsContent(
            isPinEnabled = true,
            isPinOnLaunchEnabled = true,
            isBiometricEnabled = false,
            isBiometrySupported = true,
        )
    }
}
