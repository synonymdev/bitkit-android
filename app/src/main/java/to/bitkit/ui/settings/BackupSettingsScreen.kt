package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.NavButton
import to.bitkit.ui.navigateToBackupWalletSettings
import to.bitkit.ui.navigateToRestoreWalletSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn

@Composable
fun BackupSettingsScreen(
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.settings__backup__title), onBackClick = { navController.popBackStack() })
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            NavButton(stringResource(R.string.settings__backup__wallet)) { navController.navigateToBackupWalletSettings() }
            NavButton(stringResource(R.string.settings__backup__reset)) { navController.navigateToRestoreWalletSettings() }
        }
    }
}
