package to.bitkit.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ext.toLocalizedTimestamp
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.uiIcon
import to.bitkit.models.uiTitle
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.backupsViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.BackupCategoryUiState
import to.bitkit.viewmodels.BackupStatusUiState
import to.bitkit.viewmodels.toUiState

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
    val viewModel = backupsViewModel ?: return

    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackupSettingsScreenContent(
        uiState = uiState,
        onBackupClick = { app.showSheet(BottomSheetType.BackupNavigation) },
        onResetAndRestoreClick = {
            if (isPinEnabled) {
                navController.navigateToAuthCheck(onSuccessActionId = AuthCheckAction.NAV_TO_RESET)
            } else {
                navController.navigate(Routes.ResetAndRestoreSettings)
            }
        },
        onRetryBackup = { category -> viewModel.retryBackup(category) },
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
    )
}

@Composable
private fun BackupSettingsScreenContent(
    uiState: BackupStatusUiState,
    onBackupClick: () -> Unit,
    onResetAndRestoreClick: () -> Unit,
    onRetryBackup: (BackupCategory) -> Unit,
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

            SectionHeader(title = stringResource(R.string.settings__backup__latest))

            uiState.categories.map { categoryUiState ->
                BackupStatusItem(
                    uiState = categoryUiState,
                    onRetryClick = onRetryBackup,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BackupStatusItem(
    uiState: BackupCategoryUiState,
    onRetryClick: (BackupCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = uiState.status

    val subtitle = when {
        status.running -> "Running" // TODO add missing localized text
        status.synced >= status.required -> stringResource(R.string.settings__backup__status_success)
            .replace("{time}", status.synced.toLocalizedTimestamp())

        else -> stringResource(R.string.settings__backup__status_failed)
            .replace("{time}", status.synced.toLocalizedTimestamp())
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        BackupStatusIcon(
            status = uiState.status,
            iconRes = uiState.category.uiIcon(),
        )

        Column(modifier = Modifier.weight(1f)) {
            BodyMSB(text = stringResource(uiState.category.uiTitle()))
            CaptionB(text = subtitle, color = Colors.White64, maxLines = 1)
        }

        val showRetry = !uiState.disableRetry && !status.running && status.synced < status.required
        if (showRetry) {
            BackupRetryButton(
                onClick = { onRetryClick(uiState.category) },
            )
        }
    }
}

@Composable
private fun BackupStatusIcon(
    status: BackupItemStatus,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .background(
                color = when {
                    status.running -> Colors.Yellow16
                    status.synced >= status.required -> Colors.Green16
                    else -> Colors.Red16
                },
                shape = CircleShape
            )
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = when {
                status.running -> Colors.Yellow
                status.synced >= status.required -> Colors.Green
                else -> Colors.Red
            },
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun BackupRetryButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .background(color = Colors.White16, shape = CircleShape)
            .clickableAlpha { onClick() }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_clockwise),
            contentDescription = stringResource(R.string.common__retry),
            tint = Colors.Brand,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Preview
@Composable
private fun Preview() {
    val categories = BackupCategory.entries
        .map { it.toUiState() }
        .map {
            val minutesAgo = (5..35).random().toLong()
            val timestamp = System.currentTimeMillis() - (minutesAgo * 60 * 1000)

            when (it.category) {
                BackupCategory.LDK_ACTIVITY -> it.copy(disableRetry = true)
                BackupCategory.WALLET -> it.copy(status = BackupItemStatus(running = true, required = 1))
                BackupCategory.METADATA -> it.copy(status = BackupItemStatus(required = 1))
                else -> it.copy(status = BackupItemStatus(synced = timestamp, required = timestamp))
            }
        }

    AppThemeSurface {
        BackupSettingsScreenContent(
            uiState = BackupStatusUiState(categories = categories),
            onBackupClick = {},
            onResetAndRestoreClick = {},
            onRetryBackup = {},
            onBack = {},
            onClose = {},
        )
    }
}
