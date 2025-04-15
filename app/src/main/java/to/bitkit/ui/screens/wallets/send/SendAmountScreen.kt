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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import okhttp3.internal.toLongOrDefault
import to.bitkit.R
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.Keyboard
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.OutlinedColorButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.CurrencyUiState
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import java.math.BigDecimal

@Composable
fun SendAmountScreen(
    uiState: SendUiState,
    walletUiState: MainUiState,
    currencyUiState: CurrencyUiState = LocalCurrencies.current,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    val currencyVM = currencyViewModel ?: return

    var input: String by remember { mutableStateOf("") }

    LaunchedEffect(currencyUiState.primaryDisplay) {
        input = when(currencyUiState.primaryDisplay) {
            PrimaryDisplay.BITCOIN -> {
                val amountLong = currencyVM.convertFiatToSats(input.toDoubleOrNull() ?: 0.0) ?: 0
                if (amountLong > 0.0) amountLong.toString() else ""
            }

            PrimaryDisplay.FIAT -> {
                val convertedAmount = currencyVM.convert(input.toLongOrDefault(0L))
                if ((convertedAmount?.value ?: BigDecimal(0)) > BigDecimal(0)) convertedAmount?.formatted.toString() else ""
            }
        }
    }

    LaunchedEffect(input) {
        val sats: String = when(currencyUiState.primaryDisplay) {
            PrimaryDisplay.BITCOIN -> {
                if (currencyUiState.displayUnit == BitcoinDisplayUnit.MODERN) input else (input.toLongOrDefault(0L) * 100_000_000).toString()
            }

            PrimaryDisplay.FIAT -> {
                val convertedAmount = currencyVM.convertFiatToSats(input.toDoubleOrNull() ?: 0.0) ?: 0L
                convertedAmount.toString()
            }
        }
        onEvent(SendEvent.AmountChange(value = sats))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
    ) {
        SheetTopBar(stringResource(R.string.title_send_amount)) {
            onEvent(SendEvent.AmountReset)
            onBack()
        }

        if (walletUiState.nodeLifecycleState is NodeLifecycleState.Running) {
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                NumberPadTextField(input = input, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(24.dp))
                Spacer(modifier = Modifier.weight(1f))

                Text13Up(
                    text = stringResource(R.string.wallet__send_available),
                    color = Colors.White64,
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val balances = LocalBalances.current
                    val availableAmount = when (uiState.payMethod) {
                        SendMethod.ONCHAIN -> balances.totalOnchainSats.toLong()
                        SendMethod.LIGHTNING -> balances.totalLightningSats.toLong()
                    }
                    MoneySSB(sats = availableAmount.toLong())

                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedColorButton(
                        onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
                        enabled = uiState.isUnified,
                        color = when (uiState.payMethod) {
                            SendMethod.ONCHAIN -> Colors.Brand
                            SendMethod.LIGHTNING -> Colors.Purple
                        },
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text13Up(
                            text = when (uiState.payMethod) {
                                SendMethod.ONCHAIN -> stringResource(R.string.savings)
                                SendMethod.LIGHTNING -> stringResource(R.string.spending)
                            },
                            color = when (uiState.payMethod) {
                                SendMethod.ONCHAIN -> Colors.Brand
                                SendMethod.LIGHTNING -> Colors.Purple
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    UnitButton(
                        modifier = Modifier.height(28.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                Keyboard(
                    onClick = { number ->
                        if (input == "0") input = number else input+=number
                    },
                    onClickBackspace = {
                        input = if (input.length > 1) input.dropLast(1) else "0"
                    },
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
        } else {
            SyncNodeView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview1() {
    AppThemeSurface {
        SendAmountScreen(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
                amountInput = "100"
            ),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Running
            ),
            onBack = {},
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        SendAmountScreen(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
                amountInput = "100"
            ),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Initializing
            ),
            onBack = {},
            onEvent = {},
        )
    }
}
