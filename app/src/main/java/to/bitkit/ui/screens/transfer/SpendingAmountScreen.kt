package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.AmountInput
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferEffect
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingAmountScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onOrderCreated: () -> Unit = {},
    toastException: (Throwable) -> Unit,
    toast: (title: String, description: String) -> Unit,
) {
    val currencies = LocalCurrencies.current
    val uiState by viewModel.spendingUiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.updateLimits(retry = true)
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

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .imePadding()
        ) {

            Spacer(modifier = Modifier.height(32.dp))
            Display(
                text = stringResource(R.string.lightning__spending_amount__title)
                    .withAccent(accentColor = Colors.Purple)
            )
            Spacer(modifier = Modifier.height(32.dp))

            AmountInput(
                primaryDisplay = currencies.primaryDisplay,
                overrideSats = uiState.overrideSats,
                onSatsChange = viewModel::onAmountChanged,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Actions
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
                    MoneySSB(sats = uiState.balanceAfterFee)
                }
                Spacer(modifier = Modifier.weight(1f))
                UnitButton(color = Colors.Purple)
                // 25% Button
                NumberPadActionButton(
                    text = stringResource(R.string.lightning__spending_amount__quarter),
                    color = Colors.Purple,
                    onClick = viewModel::onClickQuarter,
                )
                // Max Button
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = viewModel::onClickMaxAmount,
                )
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = viewModel::onConfirmAmount,
                enabled = uiState.satsAmount != 0L && uiState.satsAmount <= uiState.maxAllowedToSend,
                isLoading = uiState.isLoading,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
