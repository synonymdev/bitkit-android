package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.navigateToChangePin
import to.bitkit.ui.navigateToDisablePin
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
) {
    val settings = settingsViewModel ?: return
    val app = appViewModel ?: return

    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinForPaymentsEnabled by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()
    val enableSwipeToHideBalance by settings.enableSwipeToHideBalance.collectAsStateWithLifecycle()
    val hideBalanceOnOpen by settings.hideBalanceOnOpen.collectAsStateWithLifecycle()
    val enableAutoReadClipboard by settings.enableAutoReadClipboard.collectAsStateWithLifecycle()
    val enableSendAmountWarning by settings.enableSendAmountWarning.collectAsStateWithLifecycle()

    Content(
        isPinEnabled = isPinEnabled,
        isBiometricEnabled = isBiometricEnabled,
        isPinForPaymentsEnabled = isPinForPaymentsEnabled,
        enableSwipeToHideBalance = enableSwipeToHideBalance,
        hideBalanceOnOpen = hideBalanceOnOpen,
        enableAutoReadClipboard = enableAutoReadClipboard,
        enableSendAmountWarning = enableSendAmountWarning,
        isBiometrySupported = rememberBiometricAuthSupported(),
        onPinClick = {
            if (!isPinEnabled) {
                app.showSheet(Sheet.Pin())
            } else {
                navController.navigateToDisablePin()
            }
        },
        onChangePinClick = {
            navController.navigateToChangePin()
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
    )
}

@Composable
private fun Content(
    isPinEnabled: Boolean,
    isBiometricEnabled: Boolean,
    isPinForPaymentsEnabled: Boolean,
    enableSwipeToHideBalance: Boolean,
    hideBalanceOnOpen: Boolean,
    enableAutoReadClipboard: Boolean,
    enableSendAmountWarning: Boolean,
    isBiometrySupported: Boolean,
    onPinClick: () -> Unit = {},
    onChangePinClick: () -> Unit = {},
    onPinForPaymentsClick: () -> Unit = {},
    onUseBiometricsClick: () -> Unit = {},
    onSwipeToHideBalanceClick: () -> Unit = {},
    onHideBalanceOnOpenClick: () -> Unit = {},
    onAutoReadClipboardClick: () -> Unit = {},
    onSendAmountWarningClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    ScreenColumn(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__security_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__swipe_balance_to_hide),
                isChecked = enableSwipeToHideBalance,
                onClick = onSwipeToHideBalanceClick,
                modifier = Modifier.testTag("SwipeBalanceToHide"),
            )

            if (enableSwipeToHideBalance) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__hide_balance_on_open),
                    isChecked = hideBalanceOnOpen,
                    onClick = onHideBalanceOnOpenClick,
                    modifier = Modifier.testTag("HideBalanceOnOpen"),
                )
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__clipboard),
                isChecked = enableAutoReadClipboard,
                onClick = onAutoReadClipboardClick,
                modifier = Modifier.testTag("AutoReadClipboard"),
            )

            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__warn_100),
                isChecked = enableSendAmountWarning,
                onClick = onSendAmountWarningClick,
                modifier = Modifier.testTag("SendAmountWarning"),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__security__pin),
                value = SettingsButtonValue.StringValue(
                    stringResource(
                        if (isPinEnabled) {
                            R.string.settings__security__pin_enabled
                        } else {
                            R.string.settings__security__pin_disabled
                        }
                    )
                ),
                onClick = onPinClick,
                modifier = Modifier.testTag("PINCode"),
            )
            if (isPinEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__security__pin_change),
                    onClick = onChangePinClick,
                    modifier = Modifier.testTag("PINChange"),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__security__pin_payments),
                    isChecked = isPinForPaymentsEnabled,
                    onClick = onPinForPaymentsClick,
                    modifier = Modifier.testTag("EnablePinForPayments"),
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
                    modifier = Modifier.testTag("UseBiometryInstead"),
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

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            isPinEnabled = true,
            isBiometricEnabled = false,
            isPinForPaymentsEnabled = false,
            enableSwipeToHideBalance = true,
            hideBalanceOnOpen = false,
            enableAutoReadClipboard = true,
            enableSendAmountWarning = true,
            isBiometrySupported = true,
        )
    }
}
