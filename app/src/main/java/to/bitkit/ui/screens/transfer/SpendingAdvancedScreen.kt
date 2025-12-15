package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import to.bitkit.ext.mockOrder
import to.bitkit.models.Toast
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.TransferEffect
import to.bitkit.viewmodels.TransferToSpendingUiState
import to.bitkit.viewmodels.TransferValues
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel

@Suppress("ViewModelForwarding")
@Composable
fun SpendingAdvancedScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onOrderCreated: () -> Unit = {},
    currencies: CurrencyState = LocalCurrencies.current,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
) {
    val currentOnOrderCreated by rememberUpdatedState(onOrderCreated)
    val app = appViewModel ?: return
    val state by viewModel.spendingUiState.collectAsStateWithLifecycle()
    val order = state.order ?: return
    val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    var isLoading by remember { mutableStateOf(false) }

    val transferValues by viewModel.transferValues.collectAsStateWithLifecycle()

    LaunchedEffect(order.clientBalanceSat) {
        viewModel.updateTransferValues(order.clientBalanceSat)
    }

    LaunchedEffect(amountUiState.sats) {
        viewModel.onReceivingAmountChange(amountUiState.sats)
    }

    LaunchedEffect(Unit) {
        viewModel.transferEffects.collect { effect ->
            when (effect) {
                TransferEffect.OnOrderCreated -> currentOnOrderCreated()
                is TransferEffect.ToastException -> {
                    isLoading = false
                    app.toast(effect.e)
                }

                is TransferEffect.ToastError -> {
                    isLoading = false
                    app.toast(
                        type = Toast.ToastType.ERROR,
                        title = effect.title,
                        description = effect.description,
                    )
                }
            }
        }
    }

    val isValid = transferValues.let {
        val amount = amountUiState.sats.toULong()
        amount > 0u && it.maxLspBalance > 0u && amount in it.minLspBalance..it.maxLspBalance
    }

    Content(
        uiState = state,
        transferValues = transferValues,
        isValid = isValid,
        isLoading = isLoading,
        amountInputViewModel = amountInputViewModel,
        currencies = currencies,
        onBack = onBackClick,
        onContinue = {
            isLoading = true
            viewModel.onSpendingAdvancedContinue(amountUiState.sats)
        },
    )
}

@Suppress("ViewModelForwarding")
@Composable
private fun Content(
    uiState: TransferToSpendingUiState,
    transferValues: TransferValues,
    isValid: Boolean,
    isLoading: Boolean,
    amountInputViewModel: AmountInputViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .testTag("SpendingAdvanced")
        ) {
            VerticalSpacer(minHeight = 16.dp, maxHeight = 32.dp)

            Display(
                text = stringResource(R.string.lightning__spending_advanced__title)
                    .withAccent(accentColor = Colors.Purple)
            )

            FillHeight()

            NumberPadTextField(
                viewModel = amountInputViewModel,
                currencies = currencies,
                showSecondaryField = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SpendingAdvancedNumberField")
            )

            VerticalSpacer(16.dp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.requiredHeight(20.dp),
            ) {
                Caption13Up(
                    text = stringResource(R.string.lightning__spending_advanced__fee),
                    color = Colors.White64,
                )
                HorizontalSpacer(8.dp)
                uiState.feeEstimate?.let {
                    MoneySSB(it, showSymbol = true)
                } ?: run {
                    Caption13Up(text = "—", color = Colors.White64)
                }
            }

            FillHeight()

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                NumberPadActionButton(
                    text = stringResource(R.string.common__min),
                    color = Colors.Purple,
                    onClick = { amountInputViewModel.setSats(transferValues.minLspBalance.toLong(), currencies) },
                    modifier = Modifier.testTag("SpendingAdvancedMin")
                )
                NumberPadActionButton(
                    text = stringResource(R.string.common__default),
                    color = Colors.Purple,
                    onClick = { amountInputViewModel.setSats(transferValues.defaultLspBalance.toLong(), currencies) },
                    modifier = Modifier.testTag("SpendingAdvancedDefault")
                )
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = { amountInputViewModel.setSats(transferValues.maxLspBalance.toLong(), currencies) },
                    modifier = Modifier.testTag("SpendingAdvancedMax")
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
                onClick = onContinue,
                enabled = !isLoading && isValid,
                isLoading = isLoading,
                modifier = Modifier.testTag("SpendingAdvancedContinue")
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
            uiState = TransferToSpendingUiState(
                order = mockOrder().copy(clientBalanceSat = 100_000u),
                receivingAmount = 55_000L,
                feeEstimate = 2_500L,
            ),
            transferValues = TransferValues(
                defaultLspBalance = 50_000u,
                minLspBalance = 10_000u,
                maxLspBalance = 90_000u,
            ),
            isValid = true,
            amountInputViewModel = previewAmountInputViewModel(),
            isLoading = false,
            onBack = {},
            onContinue = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLoading() {
    AppThemeSurface {
        Content(
            uiState = TransferToSpendingUiState(
                order = mockOrder().copy(clientBalanceSat = 50_000u),
                receivingAmount = 20_000L,
                feeEstimate = null,
                isLoading = true,
            ),
            transferValues = TransferValues(
                defaultLspBalance = 25_000u,
                minLspBalance = 10_000u,
                maxLspBalance = 40_000u,
            ),
            isValid = true,
            amountInputViewModel = previewAmountInputViewModel(),
            isLoading = true,
            onBack = {},
            onContinue = {},
        )
    }
}
