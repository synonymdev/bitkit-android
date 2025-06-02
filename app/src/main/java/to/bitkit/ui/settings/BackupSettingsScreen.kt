package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToBackupWalletSettings
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.navigateToRestoreWalletSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun BackupSettingsScreen(
    navController: NavController,
) {
    BackupSettingsScreenContent(
        onBackupClick = { navController.navigateToBackupWalletSettings() },
        onResetAndRestoreClick = { navController.navigateToRestoreWalletSettings() },
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
    )
}

@Composable
private fun BackupSettingsScreenContent(
    onBackupClick: () -> Unit,
    onResetAndRestoreClick: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__backup__title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup__wallet),
                onClick = onBackupClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup__reset),
                onClick = onResetAndRestoreClick,
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        BackupSettingsScreenContent(
            onBackupClick = {},
            onResetAndRestoreClick = {},
            onBack = {},
            onClose = {},
        )
    }
}
