package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

/**
 * The emergency fees the force transfer pays on-chain: from savings when it can cover them, else from another wallet.
 */
@Composable
fun SparkExitFundingScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onPayFromSavings: () -> Unit,
    onPayExternally: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onFundingShown() }
    // Priced again once another wallet's payment shows: savings then sends only the rest.
    LaunchedEffect(uiState.savingsFundingSats) { viewModel.loadSavingsFunding() }
    Content(
        uiState = uiState,
        onBack = onBack,
        onContinue = {
            if (uiState.paysExternally) {
                viewModel.onPayExternally()
                onPayExternally()
            } else {
                viewModel.onFundFromSavings()
                onPayFromSavings()
            }
        },
    )
}

@Composable
private fun Content(
    uiState: SparkExitUiState,
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
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
            Display(text = stringResource(R.string.lightning__spark_exit__fees_title).withExitAccent())
            BodyM(
                text = stringResource(R.string.lightning__spark_exit__fees_text),
                color = Colors.White64,
            )
            VerticalSpacer(16.dp)
            val savingsCost = uiState.savingsCostSats
            when {
                uiState.paysExternally -> SparkExitFeesRow(
                    label = stringResource(R.string.lightning__spark_exit__fees_other_wallet),
                    sats = uiState.fundingSats,
                    modifier = Modifier.testTag("SparkExitFeesExternal")
                )
                savingsCost != null -> SparkExitFeesRow(
                    label = stringResource(R.string.lightning__spark_exit__fees_savings_note),
                    sats = savingsCost,
                    modifier = Modifier.testTag("SparkExitFeesSavings")
                )
            }
            if (uiState.paysExternally || savingsCost != null) {
                VerticalSpacer(16.dp)
                BodyS(
                    text = stringResource(R.string.lightning__spark_exit__fees_unused),
                    color = Colors.White64,
                )
            }
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__cancel),
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onContinue,
                    isLoading = uiState.isLoadingFees,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("SparkExitFundingContinue")
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
        Content(uiState = SparkExitUiState(savingsFeeSats = 490uL, canFundFromSavings = true))
    }
}
