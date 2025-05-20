package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.viewmodels.WalletViewModel
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.components.NavButton
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToBackupSettings
import to.bitkit.ui.navigateToChannelOrdersSettings
import to.bitkit.ui.navigateToDevSettings
import to.bitkit.ui.navigateToGeneralSettings
import to.bitkit.ui.navigateToLightning
import to.bitkit.ui.navigateToRegtestSettings
import to.bitkit.ui.navigateToSecuritySettings
import to.bitkit.ui.navigateToLogs
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors

@Composable
fun SettingsScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    val activity = activityListViewModel ?: return

    ScreenColumn {
        AppTopBar(stringResource(R.string.settings), onBackClick = { navController.popBackStack() })
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__general_title),
                iconRes = R.drawable.ic_settings_general,
                onClick = { navController.navigateToGeneralSettings() },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__security_title),
                iconRes = R.drawable.ic_settings_security,
                onClick = { navController.navigateToSecuritySettings() },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup_title),
                iconRes = R.drawable.ic_settings_backup,
                onClick = { navController.navigateToBackupSettings() },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__advanced_title),
                iconRes = R.drawable.ic_settings_advanced,
                enabled = false,
                onClick = { /* TODO nav to advanced */ },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__support_title),
                iconRes = R.drawable.ic_settings_support,
                enabled = false,
                onClick = { /* TODO nav to support */ },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__about_title),
                iconRes = R.drawable.ic_settings_about,
                enabled = false,
                onClick = { /* TODO nav to about */ },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__dev_title),
                iconRes = R.drawable.ic_settings_dev,
                onClick = { navController.navigateToDevSettings() },
            )

            Caption13Up(
                text = stringResource(R.string.settings__adv__section_other),
                color = Colors.White64,
                modifier = Modifier.padding(top = 24.dp)
            )
            SettingsButtonRow("Lightning") { navController.navigateToLightning() }
            SettingsButtonRow("Channel Orders") { navController.navigateToChannelOrdersSettings() }
            SettingsButtonRow("Logs") { navController.navigateToLogs() }

            if (Env.network == Network.REGTEST) {
                Caption13Up(
                    text = "REGTEST ONLY",
                    color = Colors.White64,
                    modifier = Modifier.padding(top = 24.dp)
                )
                SettingsButtonRow("Blocktank Regtest") { navController.navigateToRegtestSettings() }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NavButton("Reset All Activities", showIcon = false) { activity.removeAllActivities() }
                    NavButton("Generate Test Activities", showIcon = false) { activity.generateRandomTestData() }
                    NavButton("Wipe Wallet", showIcon = false) { viewModel.wipeStorage() }
                }
            }
        }
    }
}
