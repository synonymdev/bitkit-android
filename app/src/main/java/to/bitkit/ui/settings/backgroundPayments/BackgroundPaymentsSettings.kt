package to.bitkit.ui.settings.backgroundPayments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.NotificationPreview
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.openNotificationSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.RequestNotificationPermissions
import to.bitkit.viewmodels.SettingsViewModel

@Composable
fun BackgroundPaymentsSettings(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()
    val keepActive by settingsViewModel.keepBitkitActiveInBackground.collectAsStateWithLifecycle()

    RequestNotificationPermissions(
        onPermissionChange = settingsViewModel::setNotificationPreference,
        showPermissionDialog = false,
    )

    Content(
        hasPermission = notificationsGranted,
        keepActive = keepActive,
        onBack = onBack,
        onSystemSettingsClick = context::openNotificationSettings,
        onKeepActiveClick = { settingsViewModel.setKeepBitkitActiveInBackground(!keepActive) },
    )
}

@Composable
private fun Content(
    hasPermission: Boolean,
    keepActive: Boolean,
    onBack: () -> Unit,
    onSystemSettingsClick: () -> Unit,
    onKeepActiveClick: () -> Unit,
) {
    Column(
        modifier = Modifier.screen()
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__bg__title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)

            SettingsSwitchRow(
                title = stringResource(R.string.settings__bg__switch_title),
                isChecked = hasPermission,
                onClick = onSystemSettingsClick,
            )

            if (hasPermission) {
                BodyM(
                    text = stringResource(R.string.settings__bg__enabled),
                    color = Colors.White64,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            AnimatedVisibility(
                visible = !hasPermission,
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                BodyMB(
                    text = stringResource(R.string.settings__bg__disabled),
                    color = Colors.Red,
                )
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings__bg__keep_active_title),
                isChecked = keepActive,
                onClick = onKeepActiveClick,
                enabled = hasPermission,
            )

            BodyM(
                text = stringResource(R.string.settings__bg__keep_active_desc),
                color = Colors.White64,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            VerticalSpacer(32.dp)

            Text13Up(
                text = stringResource(R.string.settings__bg__notifications_header),
                color = Colors.White64
            )

            VerticalSpacer(16.dp)

            SecondaryButton(
                stringResource(R.string.settings__bg__customize),
                icon = { Image(painter = painterResource(R.drawable.ic_bell), contentDescription = null) },
                onClick = onSystemSettingsClick,
            )

            VerticalSpacer(32.dp)

            Text13Up(
                text = stringResource(R.string.common__preview),
                color = Colors.White64
            )

            VerticalSpacer(16.dp)

            NotificationPreview(
                enabled = hasPermission,
                title = stringResource(R.string.notification__received__title),
                description = "₿ 21 000 ($21.00)",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview1() {
    AppThemeSurface {
        Content(
            hasPermission = true,
            keepActive = true,
            onBack = {},
            onSystemSettingsClick = {},
            onKeepActiveClick = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            hasPermission = false,
            keepActive = false,
            onBack = {},
            onSystemSettingsClick = {},
            onKeepActiveClick = {},
        )
    }
}
