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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
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

object SecuritySettingsTestTags {
    const val SCREEN_CONTENT = "security_settings_content"
    const val SWIPE_TO_HIDE_BALANCE = "security_settings_swipe_to_hide_balance"
    const val HIDE_BALANCE_ON_OPEN = "security_settings_hide_balance_on_open"
    const val AUTO_READ_CLIPBOARD = "security_settings_auto_read_clipboard"
    const val SEND_AMOUNT_WARNING = "security_settings_send_amount_warning"
    const val PIN_SETUP = "security_settings_pin_setup"
    const val PIN_CHANGE = "security_settings_pin_change"
    const val PIN_ON_LAUNCH = "security_settings_pin_on_launch"
    const val PIN_ON_IDLE = "security_settings_pin_on_idle"
    const val PIN_FOR_PAYMENTS = "security_settings_pin_for_payments"
    const val USE_BIOMETRICS = "security_settings_use_biometrics"
}

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
) {
    val settings = settingsViewModel ?: return

    var showPinSheet by remember { mutableStateOf(false) }
    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val isPinOnLaunchEnabled by settings.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinOnIdleEnabled by settings.isPinOnIdleEnabled.collectAsStateWithLifecycle()
    val isPinForPaymentsEnabled by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()
    val enableSwipeToHideBalance by settings.enableSwipeToHideBalance.collectAsStateWithLifecycle()
    val hideBalanceOnOpen by settings.hideBalanceOnOpen.collectAsStateWithLifecycle()
    val enableAutoReadClipboard by settings.enableAutoReadClipboard.collectAsStateWithLifecycle()
    val enableSendAmountWarning by settings.enableSendAmountWarning.collectAsStateWithLifecycle()

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
            enableSwipeToHideBalance = enableSwipeToHideBalance,
            hideBalanceOnOpen = hideBalanceOnOpen,
            enableAutoReadClipboard = enableAutoReadClipboard,
            enableSendAmountWarning = enableSendAmountWarning,
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
            onSwipeToHideBalanceClick = {
                settings.setEnableSwipeToHideBalance(!enableSwipeToHideBalance)
            },
            onHideBalanceOnOpenClick = {
                settings.setHideBalanceOnOpen(!hideBalanceOnOpen)
            },
            onAutoReadClipboardClick = {
                settings.setEnableAutoReadClipboard(!enableAutoReadClipboard)
            },
            onSendAmountWarningClick = {
                settings.setEnableSendAmountWarning(!enableSendAmountWarning)
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
    enableSwipeToHideBalance: Boolean,
    hideBalanceOnOpen: Boolean,
    enableAutoReadClipboard: Boolean,
    enableSendAmountWarning: Boolean,
    isBiometrySupported: Boolean,
    onPinClick: () -> Unit = {},
    onChangePinClick: () -> Unit = {},
    onPinOnLaunchClick: () -> Unit = {},
    onPinOnIdleClick: () -> Unit = {},
    onPinForPaymentsClick: () -> Unit = {},
    onUseBiometricsClick: () -> Unit = {},
    onSwipeToHideBalanceClick: () -> Unit = {},
    onHideBalanceOnOpenClick: () -> Unit = {},
    onAutoReadClipboardClick: () -> Unit = {},
    onSendAmountWarningClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .testTag(SecuritySettingsTestTags.SCREEN_CONTENT)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__security_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__swipe_balance_to_hide),
                isChecked = enableSwipeToHideBalance,
                onClick = onSwipeToHideBalanceClick,
                modifier = Modifier.testTag(SecuritySettingsTestTags.SWIPE_TO_HIDE_BALANCE),
            )

            if (enableSwipeToHideBalance) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__hide_balance_on_open),
                    isChecked = hideBalanceOnOpen,
                    onClick = onHideBalanceOnOpenClick,
                    modifier = Modifier.testTag(SecuritySettingsTestTags.HIDE_BALANCE_ON_OPEN),
                )
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__clipboard),
                isChecked = enableAutoReadClipboard,
                onClick = onAutoReadClipboardClick,
                modifier = Modifier.testTag(SecuritySettingsTestTags.AUTO_READ_CLIPBOARD),
            )

            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__warn_100),
                isChecked = enableSendAmountWarning,
                onClick = onSendAmountWarningClick,
                modifier = Modifier.testTag(SecuritySettingsTestTags.SEND_AMOUNT_WARNING),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__security__pin),
                value = SettingsButtonValue.StringValue(
                    stringResource(
                        if (isPinEnabled) R.string.settings__security__pin_enabled else R.string.settings__security__pin_disabled
                    )
                ),
                onClick = onPinClick,
                modifier = Modifier.testTag(SecuritySettingsTestTags.PIN_SETUP),
            )
            if (isPinEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__security__pin_change),
                    onClick = onChangePinClick,
                    modifier = Modifier.testTag(SecuritySettingsTestTags.PIN_CHANGE),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_launch),
                    isChecked = isPinOnLaunchEnabled,
                    onClick = onPinOnLaunchClick,
                    modifier = Modifier.testTag(SecuritySettingsTestTags.PIN_ON_LAUNCH),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_idle),
                    isChecked = isPinOnIdleEnabled,
                    onClick = onPinOnIdleClick,
                    modifier = Modifier.testTag(SecuritySettingsTestTags.PIN_ON_IDLE),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_payments),
                    isChecked = isPinForPaymentsEnabled,
                    onClick = onPinForPaymentsClick,
                    modifier = Modifier.testTag(SecuritySettingsTestTags.PIN_FOR_PAYMENTS),
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
                    modifier = Modifier.testTag(SecuritySettingsTestTags.USE_BIOMETRICS),
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
            enableSwipeToHideBalance = true,
            hideBalanceOnOpen = false,
            enableAutoReadClipboard = true,
            enableSendAmountWarning = true,
            isBiometrySupported = true,
        )
    }
}
