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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.toRelativeTimeString
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.backupsViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.BackupCategoryUiState
import to.bitkit.viewmodels.BackupStatusUiState
import to.bitkit.viewmodels.toUiState
import kotlin.time.ExperimentalTime

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
        onBackupClick = { app.showSheet(Sheet.Backup()) },
        onResetAndRestoreClick = {
            if (isPinEnabled) {
                navController.navigateToAuthCheck(onSuccessActionId = AuthCheckAction.NAV_TO_RESET)
            } else {
                navController.navigate(Routes.ResetAndRestoreSettings)
            }
        },
        onRetryBackup = { category -> viewModel.retryBackup(category) },
        onBack = { navController.popBackStack() },
    )
}

@Composable
private fun BackupSettingsScreenContent(
    uiState: BackupStatusUiState,
    onBackupClick: () -> Unit,
    onResetAndRestoreClick: () -> Unit,
    onRetryBackup: (BackupCategory) -> Unit,
    onBack: () -> Unit,
) {
    val allSynced = uiState.categories.all { !it.status.isRequired }
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__backup__title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .testTag("BackupScrollView")
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup__wallet),
                onClick = onBackupClick,
                modifier = Modifier.testTag("BackupWallet"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__backup__reset),
                onClick = onResetAndRestoreClick,
                modifier = Modifier.testTag("ResetAndRestore"),
            )
            VerticalSpacer(28.dp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption13Up(
                    text = stringResource(R.string.settings__backup__latest),
                    color = Colors.White64,
                )
                FillWidth()
                @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
                if (Env.isE2eTest && allSynced) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_circle),
                        contentDescription = "All Synced",
                        tint = Colors.Green,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(16.dp)
                            .testTag("AllSynced")
                    )
                }
            }
            VerticalSpacer(12.dp)

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

@OptIn(ExperimentalTime::class)
@Composable
private fun BackupStatusItem(
    uiState: BackupCategoryUiState,
    onRetryClick: (BackupCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = uiState.status

    val time = if (status.synced == 0L) {
        stringResource(R.string.common__never)
    } else {
        status.synced.toRelativeTimeString()
    }

    val subtitle = when {
        status.running -> stringResource(R.string.settings__backup__status_running)
        !status.isRequired -> stringResource(R.string.settings__backup__status_success).replace("{time}", time)
        else -> stringResource(R.string.settings__backup__status_failed).replace("{time}", time)
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
            iconRes = uiState.category.icon,
        )

        Column(modifier = Modifier.weight(1f)) {
            BodyMSB(text = stringResource(uiState.category.title))
            CaptionB(text = subtitle, color = Colors.White64, maxLines = 1)
        }

        val showRetry = !uiState.disableRetry && !status.running && status.isRequired
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
                    !status.isRequired -> Colors.Green16
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
                !status.isRequired -> Colors.Green
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
        )
    }
}
