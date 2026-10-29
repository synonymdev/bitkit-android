package to.bitkit.ui.screens.transfer.primary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.PrimaryFundingPlan
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PrimaryTransferScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onSetUpPrimaryNode: () -> Unit,
    viewModel: PrimaryTransferViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.done.collect { onDone() } }

    Content(
        uiState = uiState,
        onBack = onBack,
        onConfirm = viewModel::confirm,
        onRetry = viewModel::load,
        onSetUpPrimaryNode = onSetUpPrimaryNode,
    )
}

@Composable
private fun Content(
    uiState: PrimaryTransferUiState,
    onBack: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSetUpPrimaryNode: () -> Unit = {},
) {
    ScreenColumn(modifier = Modifier.testTag("PrimaryTransfer")) {
        AppTopBar(titleText = stringResource(R.string.primary_transfer__title), onBackClick = onBack)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            VerticalSpacer(16.dp)
            val plan = uiState.plan
            when {
                !uiState.hasPrimaryNode -> BodyM(stringResource(R.string.primary_node__not_configured), color = Colors.White64)
                plan != null -> {
                    BalanceHeaderView(sats = plan.amountSats.toLong(), modifier = Modifier.fillMaxWidth())
                    VerticalSpacer(32.dp)
                    Caption13Up(stringResource(R.string.primary_transfer__fee), color = Colors.White64)
                    VerticalSpacer(8.dp)
                    MoneySSB(sats = plan.feeSats.toLong(), modifier = Modifier.testTag("PrimaryTransferFee"))
                    VerticalSpacer(24.dp)
                    BodyM(stringResource(R.string.primary_transfer__note), color = Colors.White64)
                }
                !uiState.isLoading && uiState.error == null ->
                    BodyM(stringResource(R.string.primary_transfer__nothing), color = Colors.White64)
            }
            uiState.error?.let {
                VerticalSpacer(16.dp)
                BodyM(it, color = Colors.Red, modifier = Modifier.testTag("PrimaryTransferError"))
            }

            FillHeight()
            when {
                !uiState.hasPrimaryNode -> PrimaryButton(
                    text = stringResource(R.string.primary_node__title),
                    onClick = onSetUpPrimaryNode,
                    modifier = Modifier.testTag("PrimaryTransferSetUp")
                )
                plan == null && uiState.error != null -> PrimaryButton(
                    text = stringResource(R.string.common__retry),
                    onClick = onRetry,
                    modifier = Modifier.testTag("PrimaryTransferRetry")
                )
                else -> PrimaryButton(
                    text = stringResource(R.string.primary_transfer__confirm),
                    onClick = onConfirm,
                    enabled = plan != null && !uiState.isLoading,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.testTag("PrimaryTransferConfirm")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = PrimaryTransferUiState(
                plan = PrimaryFundingPlan(amountSats = 48_200uL, feeSats = 600uL, opensChannel = false, isAutomatic = true),
            ),
        )
    }
}
