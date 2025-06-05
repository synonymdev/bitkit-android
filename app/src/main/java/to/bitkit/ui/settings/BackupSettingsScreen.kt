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
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

object BackupSettingsTestTags {
    const val SCREEN = "backup_settings_screen"
    const val BACKUP_BUTTON = "backup_settings_backup_button"
    const val RESTORE_BUTTON = "backup_settings_restore_button"
}

@Composable
fun BackupSettingsScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return

    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()

    BackupSettingsScreenContent(
        onBackupClick = { app.showSheet(BottomSheetType.BackupNavigation) },
        onResetAndRestoreClick = {
            if (isPinEnabled) {
                navController.navigateToAuthCheck(onSuccessActionId = AuthCheckAction.NAV_TO_RESET)
            } else {
                navController.navigate(Routes.ResetAndRestoreSettings)
            }
        },
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
                .testTag(BackupSettingsTestTags.SCREEN)
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup__wallet),
                onClick = onBackupClick,
                modifier = Modifier.testTag(BackupSettingsTestTags.BACKUP_BUTTON),
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup__reset),
                onClick = onResetAndRestoreClick,
                modifier = Modifier.testTag(BackupSettingsTestTags.RESTORE_BUTTON),
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
