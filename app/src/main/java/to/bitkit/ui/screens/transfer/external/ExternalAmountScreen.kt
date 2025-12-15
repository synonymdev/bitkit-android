package to.bitkit.ui.screens.transfer.external

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import to.bitkit.ui.LocalBalances
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
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.math.min
import kotlin.math.roundToLong

@Suppress("ViewModelForwarding")
@Composable
fun ExternalAmountScreen(
    viewModel: ExternalNodeViewModel,
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

    Content(
        amountInputViewModel = amountInputViewModel,
        amountState = uiState.amount,
        currencies = currencies,
        onContinueClick = {
            viewModel.onAmountContinue()
            onContinue()
        },
        onBackClick = onBackClick,
    )
}

@Suppress("ViewModelForwarding")
@Composable
private fun Content(
    amountInputViewModel: AmountInputViewModel,
    amountState: ExternalNodeContract.UiState.Amount = ExternalNodeContract.UiState.Amount(),
    currencies: CurrencyState = LocalCurrencies.current,
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__external__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .testTag("ExternalAmount")
        ) {
            val totalOnchainSats = LocalBalances.current.totalOnchainSats
            val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

            VerticalSpacer(16.dp)
            Display(stringResource(R.string.lightning__external_amount__title).withAccent(accentColor = Colors.Purple))
            VerticalSpacer(minHeight = 16.dp, maxHeight = 32.dp)

            NumberPadTextField(
                viewModel = amountInputViewModel,
                currencies = currencies,
                showSecondaryField = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ExternalAmountNumberField")
            )

            FillHeight()

            // Actions Row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Column {
                    Text13Up(
                        text = stringResource(R.string.wallet__send_available),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MoneySSB(sats = amountState.max, showSymbol = true)
                }
                FillWidth()
                UnitButton(
                    color = Colors.Purple,
                    onClick = { amountInputViewModel.switchUnit(currencies) },
                    modifier = Modifier.testTag("ExternalNumberPadUnit")
                )
                NumberPadActionButton(
                    text = stringResource(R.string.lightning__spending_amount__quarter),
                    color = Colors.Purple,
                    onClick = {
                        val cappedQuarter = min(
                            (totalOnchainSats.toDouble() * 0.25).roundToLong(),
                            amountState.max,
                        )
                        amountInputViewModel.setSats(cappedQuarter, currencies)
                    },
                    modifier = Modifier.testTag("ExternalAmountQuarter")
                )
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = {
                        amountInputViewModel.setSats(amountState.max, currencies)
                    },
                    modifier = Modifier.testTag("ExternalAmountMax")
                )
            }

            HorizontalDivider()
            VerticalSpacer(16.dp)

            NumberPad(
                viewModel = amountInputViewModel,
                currencies = currencies,
            )

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = { onContinueClick() },
                enabled = amountUiState.sats != 0L,
                modifier = Modifier.testTag("ExternalAmountContinue")
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Preview(showSystemUi = true, device = "id:pixel_9_pro_xl", name = "Large")
@Preview(showSystemUi = true, device = NEXUS_5, name = "Small")
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            amountState = ExternalNodeContract.UiState.Amount(max = 429_327),
            amountInputViewModel = previewAmountInputViewModel(),
        )
    }
}
