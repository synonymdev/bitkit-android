package to.bitkit.ui.settings.support

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

data class AppStatusUiState(
    val internetState: StatusUi.State = StatusUi.State.READY,
    val bitcoinNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningConnectionState: StatusUi.State = StatusUi.State.PENDING,
    val backupState: StatusUi.State = StatusUi.State.ERROR,
)

@Composable
fun AppStatusScreen(
    navController: NavController,
) {
    Content(
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
    )
}

@Composable
private fun Content(
    uiState: AppStatusUiState = AppStatusUiState(),
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__status__title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)

            StatusItem(
                statusUi = StatusUi(
                    title = "Internet",
                    subtitle = "Connected",
                    iconRes = R.drawable.ic_globe,
                    state = uiState.internetState,
                ),
                showDivider = true,
            )

            StatusItem(
                statusUi = StatusUi(
                    title = "Bitcoin Node",
                    subtitle = "Connected",
                    iconRes = R.drawable.ic_bitcoin,
                    state = uiState.bitcoinNodeState,
                ),
                showDivider = true,
            )

            StatusItem(
                statusUi = StatusUi(
                    title = "Lightning Node",
                    subtitle = "Synced",
                    iconRes = R.drawable.ic_broadcast,
                    state = uiState.lightningNodeState,
                ),
                showDivider = true,
            )

            StatusItem(
                statusUi = StatusUi(
                    title = "Lightning Connection",
                    subtitle = "Open",
                    iconRes = R.drawable.ic_lightning,
                    state = uiState.lightningConnectionState,
                ),
                showDivider = true,
            )

            StatusItem(
                statusUi = StatusUi(
                    title = "Latest Full Data Backup",
                    subtitle = "Failed to complete a full backup",
                    iconRes = R.drawable.ic_cloud_check,
                    state = uiState.backupState,
                ),
                showDivider = false,
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun StatusItem(
    statusUi: StatusUi,
    showDivider: Boolean,
    onClick: () -> Unit = {},
) {
    val bgColor = when (statusUi.state) {
        StatusUi.State.READY -> Colors.Green16
        StatusUi.State.PENDING -> Colors.Yellow16
        StatusUi.State.ERROR -> Colors.Red16
    }
    val fgColor = when (statusUi.state) {
        StatusUi.State.READY -> Colors.Green
        StatusUi.State.PENDING -> Colors.Yellow
        StatusUi.State.ERROR -> Colors.Red
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clickableAlpha { onClick() }
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

data class StatusUi(
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val state: State,
) {
    enum class State { READY, PENDING, ERROR, }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = AppStatusUiState(
                internetState = StatusUi.State.READY,
                bitcoinNodeState = StatusUi.State.READY,
                lightningNodeState = StatusUi.State.READY,
                lightningConnectionState = StatusUi.State.PENDING,
                backupState = StatusUi.State.ERROR,
            )
        )
    }
}
