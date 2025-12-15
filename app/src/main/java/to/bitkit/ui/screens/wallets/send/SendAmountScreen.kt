package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.LnurlWithdrawData
import to.bitkit.R
import to.bitkit.ext.maxSendableSat
import to.bitkit.ext.maxWithdrawableSat
import to.bitkit.models.BalanceState
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AmountInputUiState
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.LnurlParams
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import to.bitkit.viewmodels.previewAmountInputViewModel

@Suppress("ViewModelForwarding")
@Composable
fun SendAmountScreen(
    uiState: SendUiState,
    walletUiState: MainUiState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
) {
    val app = appViewModel
    val context = LocalContext.current
    val amountInputUiState: AmountInputUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    val currentOnEvent by rememberUpdatedState(onEvent)

    LaunchedEffect(Unit) {
        if (uiState.amount > 0u) {
            amountInputViewModel.setSats(uiState.amount.toLong(), currencies)
        }
    }

    LaunchedEffect(amountInputUiState.sats) {
        currentOnEvent(SendEvent.AmountChange(amountInputUiState.sats.toULong()))
    }

    SendAmountContent(
        walletUiState = walletUiState,
        uiState = uiState,
        amountInputViewModel = amountInputViewModel,
        currencies = currencies,
        onBack = {
            onEvent(SendEvent.AmountReset)
            onBack()
        }.takeIf { canGoBack },
        onClickMax = { maxSats ->
            if (uiState.lnurl == null) {
                app?.toast(
                    type = Toast.ToastType.INFO,
                    title = context.getString(R.string.wallet__send_max_spending__title),
                    description = context.getString(R.string.wallet__send_max_spending__description)
                )
            }
            amountInputViewModel.setSats(maxSats, currencies)
        },
        onClickPayMethod = { onEvent(SendEvent.PaymentMethodSwitch) },
        onContinue = { onEvent(SendEvent.AmountContinue) },
    )
}

@Suppress("ViewModelForwarding")
@Composable
fun SendAmountContent(
    walletUiState: MainUiState,
    uiState: SendUiState,
    amountInputViewModel: AmountInputViewModel,
    modifier: Modifier = Modifier,
    balances: BalanceState = LocalBalances.current,
    currencies: CurrencyState = LocalCurrencies.current,
    onBack: (() -> Unit)? = {},
    onClickMax: (Long) -> Unit = {},
    onClickPayMethod: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = modifier
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

        SheetTopBar(
            titleText = stringResource(titleRes),
            onBack = onBack,
        )

        when (walletUiState.nodeLifecycleState) {
            is NodeLifecycleState.Running -> {
                SendAmountNodeRunning(
                    amountInputViewModel = amountInputViewModel,
                    uiState = uiState,
                    balances = balances,
                    currencies = currencies,
                    onClickPayMethod = onClickPayMethod,
                    onClickMax = onClickMax,
                    onContinue = onContinue,
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

@Suppress("ViewModelForwarding")
@Composable
private fun SendAmountNodeRunning(
    amountInputViewModel: AmountInputViewModel,
    uiState: SendUiState,
    balances: BalanceState,
    currencies: CurrencyState,
    onClickPayMethod: () -> Unit,
    onClickMax: (Long) -> Unit,
    onContinue: () -> Unit,
) {
    BoxWithConstraints {
        val maxHeight = this.maxHeight
        val isLnurlWithdraw = uiState.lnurl is LnurlParams.LnurlWithdraw

        val availableAmount = when {
            isLnurlWithdraw -> uiState.lnurl.data.maxWithdrawableSat().toLong()
            uiState.payMethod == SendMethod.ONCHAIN -> balances.maxSendOnchainSats.toLong()
            else -> balances.maxSendLightningSats.toLong()
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)

            NumberPadTextField(
                viewModel = amountInputViewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SendNumberField")
            )

            FillHeight(min = 12.dp)

            val textAvailable = when {
                uiState.lnurl is LnurlParams.LnurlWithdraw -> R.string.wallet__lnurl_w_max
                uiState.isUnified -> R.string.wallet__send_available
                uiState.payMethod == SendMethod.ONCHAIN -> R.string.wallet__send_available_savings
                uiState.payMethod == SendMethod.LIGHTNING -> R.string.wallet__send_available_spending
                else -> R.string.wallet__send_available
            }

            Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier
                        .clickableAlpha { onClickMax(availableAmount) }
                        .testTag("AvailableAmount")
                ) {
                    Text13Up(
                        text = stringResource(textAvailable),
                        color = Colors.White64,
                        modifier = Modifier.testTag("available_balance")
                    )
                    VerticalSpacer(4.dp)
                    MoneySSB(sats = availableAmount, showSymbol = true)
                }

                FillWidth()

                val isLnurl = uiState.lnurl != null
                if (!isLnurl) {
                    PaymentMethodButton(uiState = uiState, onClick = onClickPayMethod)
                }
                if (uiState.lnurl is LnurlParams.LnurlPay) {
                    val max = minOf(
                        uiState.lnurl.data.maxSendableSat().toLong(),
                        availableAmount,
                    )
                    NumberPadActionButton(
                        text = stringResource(R.string.common__max),
                        onClick = { onClickMax(max) },
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("SendAmountMax")
                    )
                }
                HorizontalSpacer(8.dp)
                UnitButton(
                    onClick = { amountInputViewModel.switchUnit(currencies) },
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("SendNumberPadUnit")
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 24.dp))

            NumberPad(
                viewModel = amountInputViewModel,
                currencies = currencies,
                availableHeight = maxHeight,
                modifier = Modifier
                    .testTag("SendAmountNumberPad")
            )

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                enabled = uiState.isAmountInputValid,
                isLoading = uiState.isLoading,
                onClick = onContinue,
                modifier = Modifier.testTag("ContinueAmount")
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun PaymentMethodButton(
    uiState: SendUiState,
    onClick: () -> Unit,
) {
    val testId = when {
        uiState.isUnified -> "switch"
        uiState.payMethod == SendMethod.ONCHAIN -> "savings"
        else -> "spending"
    }
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
        onClick = onClick,
        enabled = uiState.isUnified,
        modifier = Modifier
            .height(28.dp)
            .testTag("AssetButton-$testId")
    )
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLightningNoAmount() {
    AppThemeSurface {
        BottomSheetPreview {
            SendAmountContent(
                walletUiState = MainUiState(nodeLifecycleState = NodeLifecycleState.Running),
                uiState = SendUiState(
                    payMethod = SendMethod.LIGHTNING,
                ),
                amountInputViewModel = previewAmountInputViewModel(),
                modifier = Modifier.sheetHeight(),
                balances = BalanceState(maxSendLightningSats = 54_321u),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewUnified() {
    AppThemeSurface {
        BottomSheetPreview {
            val currencies = remember {
                CurrencyState(
                    displayUnit = BitcoinDisplayUnit.CLASSIC,
                )
            }
            SendAmountContent(
                walletUiState = MainUiState(nodeLifecycleState = NodeLifecycleState.Running),
                uiState = SendUiState(
                    payMethod = SendMethod.LIGHTNING,
                    isUnified = true,
                ),
                amountInputViewModel = previewAmountInputViewModel(currencies = currencies),
                modifier = Modifier.sheetHeight(),
                balances = BalanceState(maxSendLightningSats = 54_321u),
                currencies = currencies,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewOnchain() {
    AppThemeSurface {
        BottomSheetPreview {
            SendAmountContent(
                walletUiState = MainUiState(nodeLifecycleState = NodeLifecycleState.Running),
                uiState = SendUiState(
                    payMethod = SendMethod.ONCHAIN,
                ),
                amountInputViewModel = previewAmountInputViewModel(),
                modifier = Modifier.sheetHeight(),
                balances = BalanceState(totalOnchainSats = 654_321u),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewInitializing() {
    AppThemeSurface {
        BottomSheetPreview {
            SendAmountContent(
                walletUiState = MainUiState(nodeLifecycleState = NodeLifecycleState.Initializing),
                uiState = SendUiState(
                    payMethod = SendMethod.LIGHTNING,
                ),
                amountInputViewModel = previewAmountInputViewModel(),
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Withdraw")
@Composable
private fun PreviewWithdraw() {
    AppThemeSurface {
        BottomSheetPreview {
            SendAmountContent(
                walletUiState = MainUiState(nodeLifecycleState = NodeLifecycleState.Running),
                uiState = SendUiState(
                    payMethod = SendMethod.LIGHTNING,
                    lnurl = LnurlParams.LnurlWithdraw(
                        data = LnurlWithdrawData(
                            uri = "",
                            callback = "",
                            k1 = "",
                            defaultDescription = "Test",
                            minWithdrawable = 1_000u,
                            maxWithdrawable = 51_234_000u,
                            tag = ""
                        ),
                    ),
                ),
                amountInputViewModel = previewAmountInputViewModel(),
                modifier = Modifier.sheetHeight(),
                balances = BalanceState(totalOnchainSats = 50u, totalLightningSats = 100u),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLnurlPay() {
    AppThemeSurface {
        BottomSheetPreview {
            SendAmountContent(
                walletUiState = MainUiState(nodeLifecycleState = NodeLifecycleState.Running),
                uiState = SendUiState(
                    payMethod = SendMethod.LIGHTNING,
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
                amountInputViewModel = previewAmountInputViewModel(),
                modifier = Modifier.sheetHeight(),
                balances = BalanceState(maxSendLightningSats = 54_321u),
            )
        }
    }
}

@Preview(showSystemUi = true, device = NEXUS_5)
@Composable
private fun PreviewSmallScreen() {
    AppThemeSurface {
        BottomSheetPreview {
            SendAmountContent(
                walletUiState = MainUiState(nodeLifecycleState = NodeLifecycleState.Running),
                uiState = SendUiState(
                    payMethod = SendMethod.LIGHTNING,
                ),
                amountInputViewModel = previewAmountInputViewModel(),
                modifier = Modifier.sheetHeight(),
                balances = BalanceState(maxSendLightningSats = 54_321u),
            )
        }
    }
}
