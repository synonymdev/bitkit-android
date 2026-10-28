package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import to.bitkit.R
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PrimaryReceiveConfirmScreen(
    viewModel: PrimaryReceiveViewModel,
    onCreated: (ULong) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PrimaryReceiveConfirmContent(
        state = state,
        onConfirm = { viewModel.confirm(onCreated) },
        onRefresh = viewModel::refreshQuote,
        onBack = onBack,
    )
}

@Composable
internal fun PrimaryReceiveConfirmContent(
    state: PrimaryReceiveUiState,
    onConfirm: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("PrimaryReceiveConfirm")
    ) {
        SheetTopBar(stringResource(R.string.wallet_primary__receive_quote), onBack = onBack)
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("PrimaryReceiveDetails")
        ) {
            state.offer?.let { offer ->
                BalanceHeaderView(sats = offer.amountSats.toLong(), modifier = Modifier.fillMaxWidth())
                Caption13Up(stringResource(R.string.wallet_primary__receive_fee), color = Colors.White64)
                Title(
                    text = stringResource(
                        R.string.wallet_primary__receive_sats,
                        formatPrimaryReceiveMsat(offer.maxFeeMsat),
                    ),
                    modifier = Modifier.testTag("PrimaryReceiveFee")
                )
                Caption13Up(stringResource(R.string.wallet_primary__receive_minimum), color = Colors.White64)
                Title(
                    text = stringResource(
                        R.string.wallet_primary__receive_sats,
                        formatPrimaryReceiveMsat(offer.minimumReceiveMsat),
                    ),
                    modifier = Modifier.testTag("PrimaryReceiveMinimum")
                )
                BodyM(stringResource(R.string.wallet_primary__receive_skim), color = Colors.White64)
            }
            state.error?.let { BodyM(it, color = Colors.Red, modifier = Modifier.testTag("PrimaryReceiveError")) }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            if (state.error != null) {
                SecondaryButton(
                    text = stringResource(R.string.common__retry),
                    onClick = onRefresh,
                    enabled = !state.isLoading,
                    modifier = Modifier.testTag("PrimaryReceiveRefresh")
                )
            }
            PrimaryButton(
                text = stringResource(R.string.wallet_primary__receive_action),
                onClick = onConfirm,
                isLoading = state.isLoading,
                enabled = state.offer != null && !state.isLoading && state.error == null,
                modifier = Modifier.testTag("PrimaryReceiveApprove")
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            PrimaryReceiveConfirmContent(
                state = PrimaryReceiveUiState(offer = PrimaryReceiveOffer(100_000uL, 125_500uL, "", "")),
                onConfirm = {},
                onRefresh = {},
                onBack = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}
