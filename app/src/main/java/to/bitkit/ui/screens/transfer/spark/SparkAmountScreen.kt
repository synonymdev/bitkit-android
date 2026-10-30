package to.bitkit.ui.screens.transfer.spark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.spark.SparkFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.spark.SparkFundingContract.UiState
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AmountInputEffect
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.math.roundToLong

@Suppress("ViewModelForwarding")
@Composable
fun SparkAmountScreen(
    viewModel: SparkFundingViewModel,
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
    currencies: CurrencyState = LocalCurrencies.current,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(amountUiState.sats) {
        viewModel.onAmountChange(amountUiState.sats)
    }

    LaunchedEffect(uiState.maxSats) {
        amountInputViewModel.setMaxAmount(uiState.maxSats)
    }

    LaunchedEffect(Unit) {
        amountInputViewModel.effect.collect {
            when (it) {
                AmountInputEffect.MaxExceeded -> viewModel.onMaxExceeded()
            }
        }
    }

    LaunchedEffect(viewModel, onContinue) {
        viewModel.effects.collect {
            if (it == SideEffect.AmountReady) onContinue()
        }
    }

    Content(
        uiState = uiState,
        amountInputViewModel = amountInputViewModel,
        currencies = currencies,
        onContinueClick = viewModel::onAmountContinue,
        onBackClick = onBackClick,
    )
}

@Suppress("ViewModelForwarding")
@Composable
private fun Content(
    uiState: UiState,
    amountInputViewModel: AmountInputViewModel,
    currencies: CurrencyState = LocalCurrencies.current,
    onContinueClick: () -> Unit = {},
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
                .testTag("SpendingAmount")
        ) {
            val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

            VerticalSpacer(32.dp)
            Display(
                text = stringResource(R.string.lightning__spending_amount__title)
                    .withAccent(accentColor = Colors.Purple),
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(32.dp)

            NumberPadTextField(
                viewModel = amountInputViewModel,
                currencies = currencies,
                showSecondaryField = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SpendingAmountNumberField")
            )

            FillHeight()

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    Text13Up(
                        text = stringResource(R.string.wallet__send_available),
                        color = Colors.White64,
                    )
                    VerticalSpacer(8.dp)
                    MoneySSB(
                        sats = uiState.maxSats,
                        showSymbol = true,
                        modifier = Modifier.testTag("SpendingAmountUnit")
                    )
                }
                FillWidth()
                UnitButton(
                    color = Colors.Purple,
                    onClick = { amountInputViewModel.switchUnit(currencies) },
                    modifier = Modifier.testTag("SpendingNumberPadUnit")
                )
                NumberPadActionButton(
                    text = stringResource(R.string.lightning__spending_amount__quarter),
                    color = Colors.Purple,
                    onClick = {
                        amountInputViewModel.setSats((uiState.maxSats.toDouble() * 0.25).roundToLong(), currencies)
                    },
                    modifier = Modifier.testTag("SpendingAmountQuarter")
                )
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = { amountInputViewModel.setSats(uiState.maxSats, currencies) },
                    modifier = Modifier.testTag("SpendingAmountMax")
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

            NumberPad(
                viewModel = amountInputViewModel,
                currencies = currencies,
                enabled = !uiState.isLoading,
            )

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinueClick,
                enabled = !uiState.isLoading && amountUiState.sats in 1..uiState.maxSats,
                isLoading = uiState.isLoading,
                modifier = Modifier.testTag("SpendingAmountContinue")
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
        Content(
            uiState = UiState(maxSats = 158_234),
            amountInputViewModel = previewAmountInputViewModel(),
        )
    }
}
