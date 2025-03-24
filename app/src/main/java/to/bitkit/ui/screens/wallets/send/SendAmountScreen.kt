package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.Keyboard
import to.bitkit.ui.components.OutlinedColorButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.CurrencyUiState
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState


@Composable
fun SendAmountScreen(
    uiState: SendUiState,
    currencyUiState: CurrencyUiState = LocalCurrencies.current,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SheetTopBar(stringResource(R.string.title_send_amount)) {
            onEvent(SendEvent.AmountReset)
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BalanceHeaderView(sats = uiState.amountInput.toLong(), modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    val balances = LocalBalances.current
                    Text13Up(
                        text = stringResource(R.string.wallet__send_available),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (uiState.payMethod) {
                            SendMethod.ONCHAIN -> moneyString(balances.totalOnchainSats.toLong())
                            SendMethod.LIGHTNING -> moneyString(balances.totalLightningSats.toLong())
                        }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                OutlinedColorButton(
                    onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
                    enabled = uiState.isUnified,
                    color = when (uiState.payMethod) {
                        SendMethod.ONCHAIN -> Colors.Brand
                        SendMethod.LIGHTNING -> Colors.Purple
                    }
                ) {
                    Text(
                        text = when (uiState.payMethod) {
                            SendMethod.ONCHAIN -> stringResource(R.string.savings)
                            SendMethod.LIGHTNING -> stringResource(R.string.spending)
                        }
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                UnitButton()
            }

            Spacer(modifier = Modifier.weight(1f))

            HorizontalDivider(modifier = Modifier.padding(vertical = 32.dp))

            Keyboard(
                onClick = { number -> onEvent(SendEvent.AmountChange(number)) },
                isDecimal = currencyUiState.primaryDisplay == PrimaryDisplay.FIAT,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(41.dp))

            PrimaryButton(
                text = stringResource(R.string.continue_button),
                enabled = uiState.isAmountInputValid,
                onClick = { onEvent(SendEvent.AmountContinue(uiState.amountInput)) },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@DarkModePreview
@Composable
private fun SendAmountViewPreview() {
    AppThemeSurface {
        SendAmountScreen(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
            ),
            onBack = {},
            onEvent = {},
        )
    }
}
