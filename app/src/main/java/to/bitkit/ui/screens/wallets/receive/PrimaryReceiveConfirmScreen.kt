package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PrimaryReceiveConfirmScreen(
    amountSats: ULong,
    viewModel: PrimaryReceiveViewModel,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(amountSats) { viewModel.requestQuote(amountSats) }
    LaunchedEffect(viewModel) { viewModel.created.collect { onCreated() } }

    Content(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::retry,
        onApprove = viewModel::approve,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: PrimaryReceiveUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onApprove: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .testTag("PrimaryReceiveConfirm")
    ) {
        SheetTopBar(titleText = stringResource(R.string.primary_receive__title), onBack = onBack)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            VerticalSpacer(16.dp)
            BalanceHeaderView(sats = uiState.amountSats.toLong(), modifier = Modifier.fillMaxWidth())
            VerticalSpacer(32.dp)

            if (uiState.maxFeeMsat != null) {
                Caption13Up(stringResource(R.string.primary_receive__fee), color = Colors.White64)
                VerticalSpacer(8.dp)
                MoneySSB(sats = uiState.maxFeeSats.toLong(), modifier = Modifier.testTag("PrimaryReceiveFee"))
                VerticalSpacer(24.dp)
                Caption13Up(stringResource(R.string.primary_receive__minimum), color = Colors.White64)
                VerticalSpacer(8.dp)
                MoneySSB(sats = uiState.minimumReceivedSats.toLong(), modifier = Modifier.testTag("PrimaryReceiveMin"))
                if (uiState.channelFundingSats > 0uL) {
                    VerticalSpacer(24.dp)
                    Caption13Up(stringResource(R.string.primary_receive__capacity), color = Colors.White64)
                    VerticalSpacer(8.dp)
                    MoneySSB(
                        sats = uiState.channelFundingSats.toLong(),
                        modifier = Modifier.testTag("PrimaryReceiveCapacity")
                    )
                }
                VerticalSpacer(24.dp)
                BodyM(stringResource(R.string.primary_receive__note), color = Colors.White64)
            }

            uiState.error?.let {
                BodyM(it, color = Colors.Red, modifier = Modifier.testTag("PrimaryReceiveError"))
            }

            FillHeight()
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.error != null) {
                    SecondaryButton(
                        text = stringResource(R.string.common__retry),
                        onClick = onRetry,
                        enabled = !uiState.isLoading,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("PrimaryReceiveRetry")
                    )
                }
                PrimaryButton(
                    text = stringResource(R.string.primary_receive__create),
                    onClick = onApprove,
                    enabled = !uiState.isLoading && uiState.maxFeeMsat != null,
                    isLoading = uiState.isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("PrimaryReceiveApprove")
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
        BottomSheetPreview {
            Content(
                uiState = PrimaryReceiveUiState(
                    amountSats = 20_000uL,
                    maxFeeMsat = 120_000uL,
                    channelFundingSats = 30_000uL
                ),
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewError() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = PrimaryReceiveUiState(
                    amountSats = 20_000uL,
                    error = "Your primary node is offline or unreachable.",
                ),
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
