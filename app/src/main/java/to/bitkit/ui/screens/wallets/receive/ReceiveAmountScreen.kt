package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel

@Suppress("ViewModelForwarding")
@Composable
fun ReceiveAmountScreen(
    primaryReceiveViewModel: PrimaryReceiveViewModel,
    description: String,
    onQuoted: () -> Unit,
    onBack: () -> Unit,
    initialAmountSats: ULong? = null,
    currencies: CurrencyState = LocalCurrencies.current,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
) {
    val amountInputUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    val receiveState by primaryReceiveViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        amountInputViewModel.setMaxAmount(0L)
        if (initialAmountSats != null) {
            amountInputViewModel.setSats(initialAmountSats.toLong(), currencies)
        } else {
            amountInputViewModel.clearInput()
        }
    }

    ReceiveAmountContent(
        amountInputViewModel = amountInputViewModel,
        error = receiveState.error,
        currencies = currencies,
        isCreatingInvoice = receiveState.isLoading,
        canContinue = amountInputUiState.sats > 0,
        onBack = onBack,
        onContinue = {
            primaryReceiveViewModel.requestQuote(amountInputUiState.sats.toULong(), description, onQuoted)
        },
    )
}

@Suppress("ViewModelForwarding")
@Composable
internal fun ReceiveAmountContent(
    amountInputViewModel: AmountInputViewModel,
    isCreatingInvoice: Boolean,
    canContinue: Boolean,
    modifier: Modifier = Modifier,
    error: String? = null,
    currencies: CurrencyState = LocalCurrencies.current,
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("ReceiveAmount")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__receive_bitcoin),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                VerticalSpacer(8.dp)
                NumberPadTextField(
                    viewModel = amountInputViewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ReceiveNumberPadTextField")
                )

                VerticalSpacer(12.dp)

                error?.let {
                    BodyM(it, color = Colors.Red, modifier = Modifier.testTag("PrimaryReceiveError"))
                    VerticalSpacer(12.dp)
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FillWidth()
                    UnitButton(
                        onClick = { amountInputViewModel.switchUnit(currencies) },
                        modifier = Modifier.testTag("ReceiveNumberPadUnit")
                    )
                }

                VerticalSpacer(8.dp)
            }
            HorizontalDivider()
            BoxWithConstraints(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier.weight(1.5f).fillMaxWidth()
            ) {
                NumberPad(
                    viewModel = amountInputViewModel,
                    currencies = currencies,
                    modifier = Modifier
                        .height(maxHeight.coerceAtMost(300.dp))
                        .fillMaxWidth()
                        .testTag("ReceiveNumberPad")
                )
            }
            VerticalSpacer(12.dp)
            PrimaryButton(
                text = stringResource(R.string.wallet_primary__receive_quote),
                enabled = !isCreatingInvoice && canContinue,
                isLoading = isCreatingInvoice,
                onClick = onContinue,
                modifier = Modifier.testTag("ContinueAmount")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            ReceiveAmountContent(
                amountInputViewModel = previewAmountInputViewModel(),
                canContinue = true,
                isCreatingInvoice = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true, device = NEXUS_5)
@Composable
private fun PreviewSmallScreen() {
    AppThemeSurface {
        BottomSheetPreview {
            ReceiveAmountContent(
                amountInputViewModel = previewAmountInputViewModel(sats = 200),
                canContinue = true,
                isCreatingInvoice = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
