package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/** Pays the CPFP fee funding from savings, or shows the funding address to pay from another wallet. */
@Composable
fun SparkExitFundingScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.onFundingShown()
        viewModel.loadSavingsFunding()
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopWatchingFundingAddress() } }
    Content(
        uiState = uiState,
        onBack = onBack,
        onFundFromSavings = {
            viewModel.onFundFromSavings()
            onContinue()
        },
        onFundExternally = viewModel::onFundExternally,
        onContinue = onContinue,
    )
}

@Composable
private fun Content(
    uiState: SparkExitUiState,
    onBack: () -> Unit = {},
    onFundFromSavings: () -> Unit = {},
    onFundExternally: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    val fundingSats = uiState.fundingSats
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(32.dp)
            Display(text = stringResource(R.string.lightning__spark_exit__funding_title).withAccent())
            BodyM(
                text = stringResource(R.string.lightning__spark_exit__funding_text)
                    .replace("{amount}", sparkExitAmountText(fundingSats))
                    .withAccentBoldBright(),
                color = Colors.White64,
            )
            uiState.quote?.fundedSteps?.takeIf { it > 1 }?.let {
                VerticalSpacer(16.dp)
                BodyS(
                    text = stringResource(R.string.lightning__spark_exit__funding_split).replace("{count}", "$it"),
                    color = Colors.White64,
                    modifier = Modifier.testTag("SparkExitFundingSplit")
                )
            }
            val address = uiState.fundingAddress
            if (uiState.showsFundingAddress && address != null) {
                VerticalSpacer(24.dp)
                SparkExitFundingAddress(
                    address = address,
                    requiredSats = fundingSats,
                    seenSats = uiState.fundingSeenSats,
                    partial = uiState.prestartFunding.partial,
                    isCovered = uiState.canContinueFunding,
                )
            }
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (uiState.showsFundingAddress) {
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onContinue,
                    enabled = uiState.canContinueFunding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SparkExitFundingContinue")
                )
            } else {
                val isPricing = uiState.savingsFeeSats == null && !uiState.hasSavingsFeeFailed
                if (!isPricing && !uiState.canFundFromSavings) {
                    Caption(
                        text = stringResource(R.string.lightning__spark_exit__fund_insufficient),
                        color = Colors.White64,
                    )
                    VerticalSpacer(16.dp)
                }
                PrimaryButton(
                    text = stringResource(R.string.lightning__spark_exit__fund_savings),
                    onClick = onFundFromSavings,
                    isLoading = isPricing,
                    enabled = uiState.canFundFromSavings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SparkExitFundSavings")
                )
                VerticalSpacer(16.dp)
                SecondaryButton(
                    text = stringResource(R.string.lightning__spark_exit__fund_external),
                    onClick = onFundExternally,
                    testTag = "SparkExitFundExternal",
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
        Content(uiState = SparkExitUiState(hasSavingsFeeFailed = true))
    }
}
