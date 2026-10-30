package to.bitkit.ui.screens.transfer.arkade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.arkade.ArkadeFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.arkade.ArkadeFundingContract.UiState
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ArkadeConfirmScreen(
    viewModel: ArkadeFundingViewModel,
    onSent: () -> Unit,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = uiState.isLoading) {}

    LaunchedEffect(viewModel, onSent) {
        viewModel.effects.collect {
            if (it == SideEffect.Sent) onSent()
        }
    }

    Content(
        uiState = uiState,
        onConfirm = viewModel::onConfirm,
        onBackClick = { if (!uiState.isLoading) onBackClick() },
    )
}

@Composable
private fun Content(
    uiState: UiState,
    onConfirm: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(32.dp)
            Display(stringResource(R.string.lightning__transfer__confirm).withAccent(accentColor = Colors.Purple))
            VerticalSpacer(8.dp)

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__network_fee),
                    amount = uiState.networkFeeSats,
                )
                FeeInfo(
                    label = stringResource(R.string.lightning__arkade_claim_fee),
                    amount = uiState.claimFeeSats,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__amount),
                    amount = (uiState.amountSats - uiState.claimFeeSats).coerceAtLeast(0),
                )
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__total),
                    amount = uiState.amountSats + uiState.networkFeeSats,
                )
            }

            FillHeight()

            Image(
                painter = painterResource(id = R.drawable.coin_stack_x),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(256.dp)
                    .align(alignment = CenterHorizontally)
            )

            SwipeToConfirm(
                text = stringResource(R.string.lightning__transfer__swipe),
                loading = uiState.isLoading,
                confirmed = uiState.isLoading,
                color = Colors.Purple,
                onConfirm = onConfirm,
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(uiState = UiState(amountSats = 15_000, networkFeeSats = 420, claimFeeSats = 907))
    }
}
