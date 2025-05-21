package to.bitkit.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToBackupSettings
import to.bitkit.ui.navigateToDevSettings
import to.bitkit.ui.navigateToGeneralSettings
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.navigateToSecuritySettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.ui.theme.AppThemeSurface

private const val DEV_MODE_TAP_THRESHOLD = 5

@Composable
fun SettingsScreen(
    navController: NavController,
) {
    val appViewModel = appViewModel ?: return
    val isDevModeEnabled by appViewModel.isDevModeEnabled.collectAsStateWithLifecycle()
    var enableDevModeTapCount by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    SettingsScreenContent(
        isDevModeEnabled = isDevModeEnabled || Env.network == Network.REGTEST,
        onClose = { navController.navigateToHome() },
        onGeneralClick = { navController.navigateToGeneralSettings() },
        onSecurityClick = { navController.navigateToSecuritySettings() },
        onBackupClick = { navController.navigateToBackupSettings() },
        onAdvancedClick = { /* TODO */ },
        onSupportClick = { navController.navigate(Routes.Support) },
        onDevClick = { navController.navigateToDevSettings() },
        onCogTap = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            enableDevModeTapCount = enableDevModeTapCount + 1

            if (enableDevModeTapCount >= DEV_MODE_TAP_THRESHOLD) {
                val newValue = !isDevModeEnabled
                appViewModel.setIsDevModeEnabled(newValue)

                appViewModel.toast(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(
                        if (newValue) R.string.settings__dev_enabled_title else R.string.settings__dev_disabled_title
                    ),
                    description = context.getString(
                        if (newValue) R.string.settings__dev_enabled_message else R.string.settings__dev_disabled_message
                    ),
                )
                enableDevModeTapCount = 0
            }
        },
    )
}

@Composable
fun SettingsScreenContent(
    isDevModeEnabled: Boolean,
    onClose: () -> Unit,
    onGeneralClick: () -> Unit,
    onSecurityClick: () -> Unit,
    onBackupClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onSupportClick: () -> Unit,
    onDevClick: () -> Unit,
    onCogTap: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__settings),
            onBackClick = null,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__general_title),
                iconRes = R.drawable.ic_settings_general,
                onClick = onGeneralClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__security_title),
                iconRes = R.drawable.ic_settings_security,
                onClick = onSecurityClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup_title),
                iconRes = R.drawable.ic_settings_backup,
                onClick = onBackupClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__advanced_title),
                iconRes = R.drawable.ic_settings_advanced,
                enabled = false,
                onClick = onAdvancedClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__support_title),
                iconRes = R.drawable.ic_settings_support,
                onClick = onSupportClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__about_title),
                iconRes = R.drawable.ic_settings_about,
                enabled = false,
                onClick = {},
            )
            if (isDevModeEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__dev_title),
                    iconRes = R.drawable.ic_settings_dev,
                    onClick = onDevClick,
                )
            }
            Spacer(Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.cog),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(256.dp)
                    .clickableAlpha(1f) { onCogTap() }
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SettingsScreenContent(
            isDevModeEnabled = true,
            onClose = {},
            onGeneralClick = {},
            onSecurityClick = {},
            onBackupClick = {},
            onAdvancedClick = {},
            onSupportClick = {},
            onDevClick = {},
            onCogTap = {},
        )
    }
}
