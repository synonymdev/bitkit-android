package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.LnurlWithdrawData
import to.bitkit.R
import to.bitkit.models.BalanceState
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ext.maxSendableSat
import to.bitkit.ext.maxWithdrawableSat
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.AmountInputHandler
import to.bitkit.ui.components.Keyboard
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadTextField
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
import to.bitkit.viewmodels.LnurlParams
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState

@Composable
fun SendAmountScreen(
    uiState: SendUiState,
    walletUiState: MainUiState,
    currencyUiState: CurrencyUiState = LocalCurrencies.current,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    val currencyVM = currencyViewModel ?: return
    var input: String by remember { mutableStateOf(uiState.amountInput) }
    var overrideSats: Long? by remember { mutableStateOf(null) }

    AmountInputHandler(
        input = input,
        overrideSats = overrideSats,
        primaryDisplay = currencyUiState.primaryDisplay,
        displayUnit = currencyUiState.displayUnit,
        onInputChanged = { newInput -> input = newInput },
        onAmountCalculated = { sats ->
            onEvent(SendEvent.AmountChange(value = sats))
            overrideSats = null
        },
        currencyVM = currencyVM,
    )

    SendAmountContent(
        input = input,
        uiState = uiState,
        walletUiState = walletUiState,
        currencyUiState = currencyUiState,
        primaryDisplay = currencyUiState.primaryDisplay,
        displayUnit = currencyUiState.displayUnit,
        onInputChanged = { input = it },
        onEvent = onEvent,
        onBack = onBack,
        onClickMax = { maxSats -> overrideSats = maxSats },
    )

}

@Composable
fun SendAmountContent(
    input: String,
    walletUiState: MainUiState,
    uiState: SendUiState,
    balances: BalanceState = LocalBalances.current,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    currencyUiState: CurrencyUiState,
    onInputChanged: (String) -> Unit,
    onEvent: (SendEvent) -> Unit,
    onBack: () -> Unit,
    onClickMax: (Long) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("send_amount_screen")
    ) {
        val titleRes = when (uiState.lnurl) {
            is LnurlParams.LnurlWithdraw -> R.string.wallet__lnurl_w_title
            is LnurlParams.LnurlPay -> R.string.wallet__lnurl_p_title
            else -> R.string.wallet__send_amount
        }

        SheetTopBar(stringResource(titleRes)) {
            onEvent(SendEvent.AmountReset)
            onBack()
        }

        when (walletUiState.nodeLifecycleState) {
            is NodeLifecycleState.Running -> {
                SendAmountNodeRunning(
                    input = input,
                    uiState = uiState,
                    currencyUiState = currencyUiState,
                    onInputChanged = onInputChanged,
                    balances = balances,
                    displayUnit = displayUnit,
                    primaryDisplay = primaryDisplay,
                    onEvent = onEvent,
                    onMaxClick = onClickMax,
                )
            }

            else -> {
                SyncNodeView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("sync_node_view")
                )
            }
        }
    }
}

@Composable
private fun SendAmountNodeRunning(
    input: String,
    uiState: SendUiState,
    balances: BalanceState,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    currencyUiState: CurrencyUiState,
    onInputChanged: (String) -> Unit,
    onEvent: (SendEvent) -> Unit,
    onMaxClick: (Long) -> Unit,
) {
    val isLnurlWithdraw = uiState.lnurl is LnurlParams.LnurlWithdraw

    val availableAmount = when {
        isLnurlWithdraw -> uiState.lnurl.data.maxWithdrawableSat().toLong()
        uiState.payMethod == SendMethod.ONCHAIN -> balances.totalOnchainSats.toLong()
        else -> balances.maxSendLightningSats.toLong()
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        NumberPadTextField(
            input = input,
            displayUnit = displayUnit,
            primaryDisplay = primaryDisplay,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("amount_input_field")
        )

        Spacer(modifier = Modifier.height(24.dp))
        Spacer(modifier = Modifier.weight(1f))

        val textAvailable = when {
            uiState.lnurl is LnurlParams.LnurlWithdraw -> R.string.wallet__lnurl_w_max
            uiState.isUnified -> R.string.wallet__send_available
            uiState.payMethod == SendMethod.ONCHAIN -> R.string.wallet__send_available_savings
            uiState.payMethod == SendMethod.LIGHTNING -> R.string.wallet__send_available_spending
            else -> R.string.wallet__send_available
        }

        Text13Up(
            text = stringResource(textAvailable),
            color = Colors.White64,
            modifier = Modifier.testTag("available_balance")
        )
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TODO add onClick -> override to max amount
            MoneySSB(sats = availableAmount)

            Spacer(modifier = Modifier.weight(1f))

            val isLnurl = uiState.lnurl != null
            if (!isLnurl) {
                PaymentMethodButton(uiState = uiState, onEvent = onEvent)
            }
            if (uiState.lnurl is LnurlParams.LnurlPay) {
                val max = minOf(
                    uiState.lnurl.data.maxSendableSat().toLong(),
                    availableAmount,
                )
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    onClick = { onMaxClick(max) },
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("max_amount_button")
                )

            }
            Spacer(modifier = Modifier.width(8.dp))
            UnitButton(modifier = Modifier.height(28.dp))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Keyboard(
            onClick = { number ->
                onInputChanged(if (input == "0") number else input + number)
            },
            onClickBackspace = {
                onInputChanged(if (input.length > 1) input.dropLast(1) else "0")
            },
            isDecimal = currencyUiState.primaryDisplay == PrimaryDisplay.FIAT,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("amount_keyboard"),
        )

        Spacer(modifier = Modifier.height(41.dp))

        PrimaryButton(
            text = stringResource(R.string.common__continue),
            enabled = uiState.isAmountInputValid,
            onClick = { onEvent(SendEvent.AmountContinue(uiState.amountInput)) },
            modifier = Modifier.testTag("continue_button")
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PaymentMethodButton(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
) {
    NumberPadActionButton(
        text = when (uiState.payMethod) {
            SendMethod.ONCHAIN -> stringResource(R.string.wallet__savings__title)
            SendMethod.LIGHTNING -> stringResource(R.string.wallet__spending__title)
        },
        color = when (uiState.payMethod) {
            SendMethod.ONCHAIN -> Colors.Brand
            SendMethod.LIGHTNING -> Colors.Purple
        },
        icon = if (uiState.isUnified) R.drawable.ic_transfer else null,
        onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
        enabled = uiState.isUnified,
        modifier = Modifier
            .height(28.dp)
            .testTag("payment_method_button")
    )
}

@Preview(showSystemUi = true, name = "Running - Lightning")
@Composable
private fun PreviewRunningLightning() {
    AppThemeSurface {
        SendAmountContent(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
                amountInput = "100",
                isAmountInputValid = true,
                isUnified = false
            ),
            balances = BalanceState(totalSats = 150u, totalOnchainSats = 50u, maxSendLightningSats = 100u),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Running
            ),
            onBack = {},
            onEvent = {},
            input = "100",
            displayUnit = BitcoinDisplayUnit.MODERN,
            primaryDisplay = PrimaryDisplay.FIAT,
            currencyUiState = CurrencyUiState(),
            onInputChanged = {},
        )
    }
}

@Preview(showSystemUi = true, name = "Running - Unified")
@Composable
private fun PreviewRunningUnified() {
    AppThemeSurface {
        SendAmountContent(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
                amountInput = "100",
                isAmountInputValid = true,
                isUnified = true,
            ),
            balances = BalanceState(totalSats = 150u, totalOnchainSats = 50u, maxSendLightningSats = 100u),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Running
            ),
            onBack = {},
            onEvent = {},
            input = "100",
            displayUnit = BitcoinDisplayUnit.MODERN,
            primaryDisplay = PrimaryDisplay.FIAT,
            currencyUiState = CurrencyUiState(),
            onInputChanged = {},
        )
    }
}

@Preview(showSystemUi = true, name = "Running - Onchain")
@Composable
private fun PreviewRunningOnchain() {
    AppThemeSurface {
        SendAmountContent(
            uiState = SendUiState(
                payMethod = SendMethod.ONCHAIN,
                amountInput = "5000",
                isAmountInputValid = true,
                isUnified = false
            ),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Running
            ),
            balances = BalanceState(totalSats = 150u, totalOnchainSats = 50u, maxSendLightningSats = 100u),
            onBack = {},
            onEvent = {},
            input = "5000",
            currencyUiState = CurrencyUiState(),
            displayUnit = BitcoinDisplayUnit.MODERN,
            primaryDisplay = PrimaryDisplay.BITCOIN,
            onInputChanged = {},
        )
    }
}

@Preview(showSystemUi = true, name = "Initializing")
@Composable
private fun PreviewInitializing() {
    AppThemeSurface {
        SendAmountContent(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
                amountInput = "100"
            ),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Initializing
            ),
            balances = BalanceState(totalSats = 150u, totalOnchainSats = 50u, maxSendLightningSats = 100u),
            onBack = {},
            onEvent = {},
            displayUnit = BitcoinDisplayUnit.MODERN,
            primaryDisplay = PrimaryDisplay.BITCOIN,
            input = "100",
            currencyUiState = CurrencyUiState(),
            onInputChanged = {},
        )
    }
}

@Preview(showSystemUi = true, name = "Withdraw")
@Composable
private fun PreviewWithdraw() {
    AppThemeSurface {
        SendAmountContent(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
                amountInput = "100",
                lnurl = LnurlParams.LnurlWithdraw(
                    data = LnurlWithdrawData(
                        uri = "",
                        callback = "",
                        k1 = "",
                        defaultDescription = "Test",
                        minWithdrawable = 1u,
                        maxWithdrawable = 130u,
                        tag = ""
                    ),
                ),
            ),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Running
            ),
            balances = BalanceState(totalSats = 150u, totalOnchainSats = 50u, totalLightningSats = 100u),
            onBack = {},
            onEvent = {},
            displayUnit = BitcoinDisplayUnit.MODERN,
            primaryDisplay = PrimaryDisplay.BITCOIN,
            input = "100",
            currencyUiState = CurrencyUiState(),
            onInputChanged = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLnurlPay() {
    AppThemeSurface {
        SendAmountContent(
            uiState = SendUiState(
                payMethod = SendMethod.LIGHTNING,
                amountInput = "100",
                lnurl = LnurlParams.LnurlPay(
                    data = LnurlPayData(
                        uri = "",
                        callback = "",
                        metadataStr = "",
                        commentAllowed = 255u,
                        minSendable = 1000u,
                        maxSendable = 1000_000u,
                        allowsNostr = false,
                        nostrPubkey = null,
                    ),
                ),
            ),
            walletUiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Running
            ),
            balances = BalanceState(totalSats = 150u, totalOnchainSats = 50u, totalLightningSats = 100u),
            onBack = {},
            onEvent = {},
            displayUnit = BitcoinDisplayUnit.MODERN,
            primaryDisplay = PrimaryDisplay.BITCOIN,
            input = "100",
            currencyUiState = CurrencyUiState(),
            onInputChanged = {},
        )
    }
}
