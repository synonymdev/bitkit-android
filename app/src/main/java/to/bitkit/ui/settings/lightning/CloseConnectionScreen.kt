package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccentBoldBright

@Composable
fun CloseConnectionScreen(
    navController: NavController,
    viewModel: LightningConnectionsViewModel,
) {
    Content(
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onClickClose = { viewModel.closeChannel() },
    )
}

@Composable
private fun Content(
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onClickClose: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__close_conn),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)
            BodyM(
                text = stringResource(R.string.lightning__close_text).withAccentBoldBright(),
                color = Colors.White64,
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.exclamation_mark),
                    contentDescription = null,
                    modifier = Modifier.size(256.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__cancel),
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cancel_button"),
                )

                PrimaryButton(
                    text = stringResource(R.string.lightning__close_button),
                    onClick = onClickClose,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("close_button")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content()
    }
}
