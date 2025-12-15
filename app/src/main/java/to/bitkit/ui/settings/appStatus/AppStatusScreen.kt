package to.bitkit.ui.settings.appStatus

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ext.startActivityAppSettings
import to.bitkit.ext.toLocalizedTimestamp
import to.bitkit.models.HealthState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.AppHealthState
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.time.Clock.System.now
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

@Composable
fun AppStatusScreen(
    navController: NavController,
    viewModel: AppStatusViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onInternetClick = { context.startActivityAppSettings() },
        onElectrumClick = { navController.navigate(Routes.ElectrumConfig) },
        onNodeClick = { navController.navigate(Routes.NodeInfo) },
        onChannelsClick = { navController.navigate(Routes.LightningConnections) },
        onBackupClick = { navController.navigate(Routes.BackupSettings) },
    )
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun Content(
    uiState: AppStatusUiState = AppStatusUiState(),
    onBack: () -> Unit = {},
    onInternetClick: () -> Unit = {},
    onElectrumClick: () -> Unit = {},
    onNodeClick: () -> Unit = {},
    onChannelsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__status__title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)

            StatusItem(
                statusUi = StatusUi(
                    id = "internet",
                    title = stringResource(R.string.settings__status__internet__title),
                    subtitle = when (uiState.health.internet) {
                        HealthState.READY -> stringResource(R.string.settings__status__internet__ready)
                        HealthState.PENDING -> stringResource(R.string.settings__status__internet__pending)
                        HealthState.ERROR -> stringResource(R.string.settings__status__internet__error)
                    },
                    iconRes = R.drawable.ic_globe,
                    state = uiState.health.internet,
                ),
                onClick = onInternetClick,
            )

            StatusItem(
                statusUi = StatusUi(
                    id = "electrum",
                    title = stringResource(R.string.settings__status__electrum__title),
                    subtitle = when (uiState.health.electrum) {
                        HealthState.READY -> stringResource(R.string.settings__status__electrum__ready)
                        HealthState.PENDING -> stringResource(R.string.settings__status__electrum__pending)
                        HealthState.ERROR -> stringResource(R.string.settings__status__electrum__error)
                    },
                    iconRes = R.drawable.ic_bitcoin,
                    state = uiState.health.electrum,
                ),
                onClick = onElectrumClick,
            )

            StatusItem(
                statusUi = StatusUi(
                    id = "lightning_node",
                    title = stringResource(R.string.settings__status__lightning_node__title),
                    subtitle = uiState.nodeSubtitle.ifEmpty {
                        when (uiState.health.node) {
                            HealthState.READY -> stringResource(R.string.settings__status__lightning_node__ready)
                            HealthState.PENDING -> stringResource(R.string.settings__status__lightning_node__pending)
                            HealthState.ERROR -> stringResource(R.string.settings__status__lightning_node__error)
                        }
                    },
                    iconRes = R.drawable.ic_broadcast,
                    state = uiState.health.node,
                ),
                onClick = onNodeClick,
            )

            StatusItem(
                statusUi = StatusUi(
                    id = "lightning_connection",
                    title = stringResource(R.string.settings__status__lightning_connection__title),
                    subtitle = when (uiState.health.channels) {
                        HealthState.READY -> stringResource(R.string.settings__status__lightning_connection__ready)
                        HealthState.PENDING -> stringResource(R.string.settings__status__lightning_connection__pending)
                        HealthState.ERROR -> stringResource(R.string.settings__status__lightning_connection__error)
                    },
                    iconRes = R.drawable.ic_lightning,
                    state = uiState.health.channels,
                ),
                onClick = onChannelsClick,
            )

            StatusItem(
                statusUi = StatusUi(
                    id = "backup",
                    title = stringResource(R.string.settings__status__backup__title),
                    subtitle = uiState.backupSubtitle.ifEmpty {
                        when (uiState.health.backups) {
                            HealthState.READY -> stringResource(R.string.settings__status__backup__ready)
                            HealthState.PENDING -> stringResource(R.string.settings__status__backup__pending)
                            HealthState.ERROR -> stringResource(R.string.settings__status__backup__error)
                        }
                    },
                    iconRes = R.drawable.ic_cloud_check,
                    state = uiState.health.backups,
                ),
                showDivider = false,
                onClick = onBackupClick,
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun StatusItem(
    statusUi: StatusUi,
    showDivider: Boolean = true,
    onClick: () -> Unit = {},
) {
    val bgColor = when (statusUi.state) {
        HealthState.READY -> Colors.Green16
        HealthState.PENDING -> Colors.Yellow16
        HealthState.ERROR -> Colors.Red16
    }
    val fgColor = when (statusUi.state) {
        HealthState.READY -> Colors.Green
        HealthState.PENDING -> Colors.Yellow
        HealthState.ERROR -> Colors.Red
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clickableAlpha { onClick() }
                .testTag("Status-${statusUi.id}")
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .background(bgColor, shape = CircleShape)
            ) {
                Icon(
                    painter = painterResource(statusUi.iconRes),
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            HorizontalSpacer(16.dp)
            Column {
                BodyMSB(
                    statusUi.title,
                    modifier = Modifier
                        .height(22.dp)
                        .wrapContentHeight()
                )
                CaptionB(
                    statusUi.subtitle,
                    color = Colors.White64,
                    modifier = Modifier
                        .height(18.dp)
                        .wrapContentHeight()
                )
            }
        }
        if (showDivider) {
            HorizontalDivider()
        }
    }
}

private data class StatusUi(
    val id: String,
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val state: HealthState,
)

@OptIn(ExperimentalTime::class)
@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = AppStatusUiState(
                health = AppHealthState(
                    internet = HealthState.PENDING,
                    electrum = HealthState.READY,
                    node = HealthState.READY,
                    channels = HealthState.PENDING,
                    backups = HealthState.READY,
                ),
                backupSubtitle = now().minus(3.minutes).toEpochMilliseconds().toLocalizedTimestamp(),
                nodeSubtitle = NodeLifecycleState.Running.uiText,
            ),
        )
    }
}
