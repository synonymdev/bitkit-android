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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.Routes
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToBackupSettings
import to.bitkit.ui.navigateToDevSettings
import to.bitkit.ui.navigateToGeneralSettings
import to.bitkit.ui.navigateToSecuritySettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn

@Composable
fun SettingsScreen(
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.settings__settings), onBackClick = { navController.popBackStack() })
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
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
                onClick = { navController.navigate(Routes.Support) },
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

            Spacer(Modifier.weight(1f))
            Image(
                painter = painterResource(id = R.drawable.cog),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(256.dp)
            )
            Spacer(Modifier.weight(1f))
        }
    }
}
