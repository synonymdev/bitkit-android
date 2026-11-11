package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Explains Instant and Later, and shows the spending balance this transfer leads to. */
@Composable
fun TransferLearnMoreScreen(
    viewModel: SparkFundingViewModel,
    spendingBalanceSats: Long,
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toSpending = state.quote?.toSpendingSats(state.speed) ?: 0

    Content(
        balanceSats = spendingBalanceSats + toSpending,
        onBackClick = onBackClick,
        onContinueClick = onContinueClick,
    )
}

@Composable
private fun Content(
    balanceSats: Long,
    onBackClick: () -> Unit = {},
    onContinueClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(32.dp)
            Display(
                text = stringResource(R.string.lightning__spending_learn_more__title)
                    .withAccent(accentColor = Colors.Purple)
            )
            VerticalSpacer(8.dp)
            BodyM(text = stringResource(R.string.lightning__spending_learn_more__text), color = Colors.White64)
            VerticalSpacer(16.dp)
            BodyM(text = stringResource(R.string.lightning__spark_trust), color = Colors.White64)
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            BodyMB(text = stringResource(R.string.lightning__spending_label))
            VerticalSpacer(16.dp)

            LightningChannel(
                capacity = balanceSats,
                localBalance = balanceSats,
                remoteBalance = 0L,
                status = ChannelStatusUi.OPEN,
                showLabels = true,
                showReceiving = false,
                modifier = Modifier.testTag("TransferLearnMoreBalance")
            )

            VerticalSpacer(32.dp)
            PrimaryButton(
                text = stringResource(R.string.common__understood),
                onClick = onContinueClick,
                modifier = Modifier.testTag("LiquidityContinue")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Preview(showSystemUi = true, device = NEXUS_5, name = "Small")
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(balanceSats = 48_650L)
    }
}
