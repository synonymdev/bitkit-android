package to.bitkit.ui.screens.transfer.external

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.screens.transfer.external.ExternalNodeContract.SideEffect

@Composable
fun ExternalConfirmScreen(
    viewModel: ExternalNodeViewModel,
    onConfirm: () -> Unit,
    onNetworkFeeClick: () -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel, onConfirm) {
        viewModel.effects.collect {
            when (it) {
                SideEffect.ConfirmSuccess -> onConfirm()
                else -> Unit
            }
        }
    }

    Content(
        uiState = uiState,
        onConfirm = { viewModel.onConfirm() },
        onNetworkFeeClick = onNetworkFeeClick,
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
    )
}

@Composable
private fun Content(
    uiState: ExternalNodeContract.UiState,
    onConfirm: () -> Unit = {},
    onNetworkFeeClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__external__nav_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            val networkFee = uiState.networkFee
            val serviceFee = 0L
            val totalFee = uiState.amount.sats + networkFee

            Spacer(modifier = Modifier.height(16.dp))
            Display(text = stringResource(R.string.lightning__transfer__confirm).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                        .clickableAlpha(onClick = onNetworkFeeClick)
                ) {
                    Caption13Up(
                        text = stringResource(R.string.lightning__spending_confirm__network_fee),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AnimatedVisibility(visible = networkFee > 0L, enter = fadeIn(), exit = fadeOut()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MoneySSB(sats = networkFee)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                painterResource(R.drawable.ic_pencil_simple),
                                contentDescription = null,
                                tint = Colors.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__lsp_fee),
                    amount = serviceFee,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__amount),
                    amount = uiState.amount.sats,
                )
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__total),
                    amount = totalFee,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = ExternalNodeContract.UiState(
                amount = ExternalNodeContract.UiState.Amount(sats = 45_500L),
                networkFee = 2_100L,
            )
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewFeeLoading() {
    AppThemeSurface {
        Content(
            uiState = ExternalNodeContract.UiState(
                amount = ExternalNodeContract.UiState.Amount(sats = 45_500L),
                networkFee = 0L,
            )
        )
    }
}
