package to.bitkit.ui.screens.transfer

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
import androidx.compose.ui.platform.LocalContext
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
import to.bitkit.ui.components.SyncNodeView
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
import to.bitkit.viewmodels.TransferEffect
import to.bitkit.viewmodels.TransferToSpendingUiState
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.math.min

@Suppress("ViewModelForwarding")
@Composable
fun SpendingAmountScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onOrderCreated: () -> Unit = {},
    toastException: (Throwable) -> Unit,
    toast: (title: String, description: String) -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
) {
    val uiState by viewModel.spendingUiState.collectAsStateWithLifecycle()
    val isNodeRunning by viewModel.isNodeRunning.collectAsStateWithLifecycle()
    val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.updateLimits()
    }

    LaunchedEffect(Unit) {
        viewModel.transferEffects.collect { effect ->
            when (effect) {
                TransferEffect.OnOrderCreated -> onOrderCreated()
                is TransferEffect.ToastError -> toast(effect.title, effect.description)
                is TransferEffect.ToastException -> toastException(effect.e)
            }
        }
    }

    Content(
        isNodeRunning = isNodeRunning,
        uiState = uiState,
        amountInputViewModel = amountInputViewModel,
        currencies = currencies,
        onBackClick = onBackClick,
        onClickQuarter = {
            val quarter = uiState.balanceAfterFeeQuarter()
            val max = uiState.maxAllowedToSend
            if (quarter > max) {
                toast(
                    context.getString(R.string.lightning__spending_amount__error_max__title),
                    context.getString(R.string.lightning__spending_amount__error_max__description)
                        .replace("{amount}", "$max"),
                )
            }
            val cappedQuarter = min(quarter, max)
            viewModel.updateLimits(cappedQuarter)
            amountInputViewModel.setSats(cappedQuarter, currencies)
        },
        onClickMaxAmount = {
            val newAmountSats = uiState.maxAllowedToSend
            viewModel.updateLimits(newAmountSats)
            amountInputViewModel.setSats(newAmountSats, currencies)
        },
        onConfirmAmount = { viewModel.onConfirmAmount(amountUiState.sats) },
    )
}

@Suppress("ViewModelForwarding")
@Composable
private fun Content(
    isNodeRunning: Boolean,
    uiState: TransferToSpendingUiState,
    amountInputViewModel: AmountInputViewModel,
    onBackClick: () -> Unit,
    onClickQuarter: () -> Unit,
    onClickMaxAmount: () -> Unit,
    onConfirmAmount: () -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        if (isNodeRunning) {
            SpendingAmountNodeRunning(
                uiState = uiState,
                amountInputViewModel = amountInputViewModel,
                currencies = currencies,
                onClickQuarter = onClickQuarter,
                onClickMaxAmount = onClickMaxAmount,
                onConfirmAmount = onConfirmAmount,
            )
        } else {
            SyncNodeView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Suppress("ViewModelForwarding")
@Composable
private fun SpendingAmountNodeRunning(
    uiState: TransferToSpendingUiState,
    amountInputViewModel: AmountInputViewModel,
    currencies: CurrencyState,
    onClickQuarter: () -> Unit,
    onClickMaxAmount: () -> Unit,
    onConfirmAmount: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxSize()
            .testTag("SpendingAmount")
    ) {
        val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

        VerticalSpacer(minHeight = 16.dp, maxHeight = 32.dp)

        Display(
            text = stringResource(R.string.lightning__spending_amount__title)
                .withAccent(accentColor = Colors.Purple)
        )

        FillHeight()

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
            modifier = Modifier
                .padding(vertical = 8.dp)
                .testTag("SendAmountNumberPad")
        ) {
            Column {
                Text13Up(
                    text = stringResource(R.string.wallet__send_available),
                    color = Colors.White64,
                    modifier = Modifier.testTag("SpendingAmountAvailable")
                )
                VerticalSpacer(8.dp)
                MoneySSB(sats = uiState.balanceAfterFee, modifier = Modifier.testTag("SpendingAmountUnit"))
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
                onClick = onClickQuarter,
                modifier = Modifier.testTag("SpendingAmountQuarter")
            )
            NumberPadActionButton(
                text = stringResource(R.string.common__max),
                color = Colors.Purple,
                onClick = onClickMaxAmount,
                modifier = Modifier.testTag("SpendingAmountMax")
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
            onClick = onConfirmAmount,
            enabled = !uiState.isLoading && amountUiState.sats <= uiState.maxAllowedToSend,
            isLoading = uiState.isLoading,
            modifier = Modifier.testTag("SpendingAmountContinue")
        )

        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Preview(showSystemUi = true, device = "id:pixel_9_pro_xl", name = "Large")
@Preview(showSystemUi = true, device = NEXUS_5, name = "Small")
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            isNodeRunning = true,
            uiState = TransferToSpendingUiState(maxAllowedToSend = 158_234, balanceAfterFee = 158_234),
            amountInputViewModel = previewAmountInputViewModel(),
            currencies = CurrencyState(),
            onBackClick = {},
            onClickQuarter = {},
            onClickMaxAmount = {},
            onConfirmAmount = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewInitializing() {
    AppThemeSurface {
        Content(
            isNodeRunning = false,
            uiState = TransferToSpendingUiState(),
            amountInputViewModel = previewAmountInputViewModel(),
            currencies = CurrencyState(),
            onBackClick = {},
            onClickQuarter = {},
            onClickMaxAmount = {},
            onConfirmAmount = {},
        )
    }
}
