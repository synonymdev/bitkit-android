package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Toast
import to.bitkit.models.safe
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.HwWalletConnectionIcon
import to.bitkit.ui.components.MoneyMSB
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceContent
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadInputHandler
import to.bitkit.viewmodels.AmountInputEffect
import to.bitkit.viewmodels.AmountInputUiState
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.LnurlParams
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendFundingOption
import to.bitkit.viewmodels.SendFundingSource
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import to.bitkit.viewmodels.previewAmountInputViewModel

/** Opacity of unavailable payment sources in the balance selector. */
private const val DISABLED_FUNDING_SOURCE_ALPHA = 0.30f

@Suppress("ViewModelForwarding")
@Composable
fun SendAmountScreen(
    uiState: SendUiState,
    nodeLifecycleState: NodeLifecycleState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
    balances: BalanceState = LocalBalances.current,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
) {
    val app = appViewModel
    val context = LocalContext.current
    val amountInputUiState: AmountInputUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    val currentOnEvent by rememberUpdatedState(onEvent)

    val maxExceededMessage = run {
        val lnurl = uiState.lnurl
        val lnurlPayMaxExceeded = lnurl is LnurlParams.LnurlPay &&
            lnurl.data.maxSendableSat().toLong() <
            (balances.maxSendLightningSats.safe() - uiState.estimatedRoutingFee.safe()).toLong()
        when {
            lnurl is LnurlParams.LnurlWithdraw ->
                R.string.wallet__lnurl_w_error_max__title to R.string.wallet__lnurl_w_error_max__description
            lnurlPayMaxExceeded ->
                R.string.wallet__lnurl_pay__error_max__title to R.string.wallet__lnurl_pay__error_max__description
            else ->
                R.string.wallet__send_amount_exceeded__title to R.string.wallet__send_amount_exceeded__description
        }
    }
    val currentMaxExceededMessage by rememberUpdatedState(maxExceededMessage)

    val pendingRequests = app?.pendingPaymentRequests?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(pendingRequests, uiState.paykitUsesUsdt) {
        app?.refreshPaykitRequestAmount()
    }

    LaunchedEffect(uiState.paykitUsesUsdt, uiState.payMethod, uiState.amount.takeIf { uiState.isPaymentRequest }) {
        if (!uiState.paykitUsesUsdt && uiState.amount > 0u) {
            amountInputViewModel.setSats(uiState.amount.toLong(), currencies)
        }
    }

    LaunchedEffect(amountInputUiState.sats) {
        if (!uiState.paykitUsesUsdt) currentOnEvent(SendEvent.AmountChange(amountInputUiState.sats.toULong()))
    }

    LaunchedEffect(Unit) {
        amountInputViewModel.effect.collect {
            when (it) {
                AmountInputEffect.MaxExceeded -> {
                    val (titleRes, descriptionRes) = currentMaxExceededMessage
                    app?.toast(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(titleRes),
                        description = context.getString(descriptionRes),
                        visibilityTime = Toast.VISIBILITY_TIME_SHORT,
                        testTag = "SendAmountExceededToast",
                    )
                }
            }
        }
    }

    LaunchedEffect(uiState.decodedInvoice, uiState.payMethod) {
        if (uiState.payMethod == SendMethod.LIGHTNING && uiState.decodedInvoice != null) {
            currentOnEvent(SendEvent.EstimateMaxRoutingFee)
        }
    }

    SendAmountContent(
        nodeLifecycleState = nodeLifecycleState,
        uiState = uiState,
        amountInputViewModel = amountInputViewModel,
        currencies = currencies,
        onBack = {
            onEvent(SendEvent.AmountReset)
            onBack()
        }.takeIf { canGoBack },
        onClickMax = { maxSats ->
            if (uiState.lnurl == null && uiState.payMethod == SendMethod.LIGHTNING) {
                app?.toast(
                    type = Toast.ToastType.INFO,
                    title = context.getString(R.string.wallet__send_max_spending__title),
                    description = context.getString(R.string.wallet__send_max_spending__description)
                )
            }
            amountInputViewModel.setSats(maxSats, currencies)
        },
        onClickPayMethod = { onEvent(SendEvent.PaymentMethodSelect(it)) },
        onContinue = { onEvent(SendEvent.AmountContinue) },
    )
}

@Suppress("ViewModelForwarding")
@Composable
fun SendAmountContent(
    nodeLifecycleState: NodeLifecycleState,
    uiState: SendUiState,
    amountInputViewModel: AmountInputViewModel,
    modifier: Modifier = Modifier,
    balances: BalanceState = LocalBalances.current,
    currencies: CurrencyState = LocalCurrencies.current,
    onBack: (() -> Unit)? = {},
    onClickMax: (Long) -> Unit = {},
    onClickPayMethod: (SendFundingSource) -> Unit = {},
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
            else -> if (uiState.paykitUsesUsdt) R.string.usdt__amount else R.string.wallet__send_amount
        }

        SendContactTopBar(
            titleText = stringResource(titleRes),
            contact = uiState.contactPaymentProfile,
            onBack = onBack,
        )

        when {
            nodeLifecycleState is NodeLifecycleState.Running || uiState.paykitUsesUsdt -> {
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

@Suppress("ViewModelForwarding", "CyclomaticComplexMethod")
@Composable
private fun SendAmountNodeRunning(
    amountInputViewModel: AmountInputViewModel,
    uiState: SendUiState,
    balances: BalanceState,
    currencies: CurrencyState,
    onClickPayMethod: (SendFundingSource) -> Unit,
    onClickMax: (Long) -> Unit,
    onContinue: () -> Unit,
) {
    val app = appViewModel
    val usdtWallet = app?.usdtWallet?.collectAsStateWithLifecycle()?.value
    val hideBalance = settingsViewModel?.hideBalance?.collectAsStateWithLifecycle()?.value == true
    BoxWithConstraints {
        val maxHeight = this.maxHeight
        val isLnurlWithdraw = uiState.lnurl is LnurlParams.LnurlWithdraw

        val availableAmount = when {
            isLnurlWithdraw -> uiState.lnurl.data.maxWithdrawableSat().toLong()
            uiState.hardwareWalletId != null -> uiState.hardwareAvailableSats.toLong()
            uiState.payMethod == SendMethod.ONCHAIN -> balances.maxSendOnchainSats.toLong()
            else -> {
                val maxLightning = balances.maxSendLightningSats
                val routingFee = uiState.estimatedRoutingFee
                (maxLightning.safe() - routingFee.safe()).toLong()
            }
        }

        val maxAllowed = when (val lnurl = uiState.lnurl) {
            is LnurlParams.LnurlPay -> minOf(lnurl.data.maxSendableSat().toLong(), availableAmount)
            else -> availableAmount
        }

        LaunchedEffect(maxAllowed) {
            amountInputViewModel.setMaxAmount(maxAllowed)
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)

            if (uiState.paykitUsesUsdt) {
                UsdtAmountHeader(
                    amount = uiState.paykitAmount.ifEmpty { "0" },
                    modifier = Modifier.testTag("SendNumberField")
                )
            } else {
                NumberPadTextField(
                    viewModel = amountInputViewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SendNumberField")
                )
            }

            if (uiState.paykitUsesUsdt) VerticalSpacer(32.dp) else FillHeight(min = 12.dp)
            if (uiState.paykitRateUnavailable) {
                BodyS(
                    stringResource(R.string.wallet__payment_request_rate_unavailable),
                    color = Colors.White64
                )
            }

            val textAvailable = when {
                uiState.lnurl is LnurlParams.LnurlWithdraw -> R.string.wallet__lnurl_w_max
                uiState.hardwareWalletId != null -> R.string.wallet__send_available
                uiState.isUnified -> R.string.wallet__send_available
                uiState.payMethod == SendMethod.ONCHAIN -> R.string.wallet__send_available
                uiState.payMethod == SendMethod.LIGHTNING -> R.string.wallet__send_available_spending
                else -> R.string.wallet__send_available
            }

            Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier
                        .clickableAlpha(
                            enabled = uiState.isAmountEditable && !uiState.paykitUsesUsdt
                        ) { onClickMax(availableAmount) }
                        .testTag("AvailableAmount")
                ) {
                    Text13Up(
                        text = stringResource(textAvailable),
                        color = Colors.White64,
                        modifier = Modifier.testTag("available_balance")
                    )
                    VerticalSpacer(4.dp)
                    if (uiState.paykitUsesUsdt) {
                        val balance = if (hideBalance) {
                            UiConstants.HIDE_BALANCE_SHORT
                        } else {
                            usdtWallet?.balance?.let { "$ " + usdtOverviewAmount(it) } ?: "—"
                        }
                        BodySSB(balance)
                    } else {
                        MoneySSB(sats = availableAmount, showSymbol = true)
                    }
                }

                FillWidth()

                val isLnurl = uiState.lnurl != null
                if (!isLnurl || uiState.usdtRecipient != null) {
                    PaymentMethodButton(uiState = uiState, onClick = onClickPayMethod)
                }
                if (!uiState.isPaymentRequest && !uiState.paykitUsesUsdt && uiState.lnurl is LnurlParams.LnurlPay) {
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
                if (!uiState.paykitUsesUsdt) {
                    UnitButton(
                        onClick = { amountInputViewModel.switchUnit(currencies) },
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("SendNumberPadUnit")
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

            if (uiState.paykitUsesUsdt) {
                NumberPad(
                    type = NumberPadType.DECIMAL,
                    enabled = uiState.isAmountEditable,
                    onPress = {
                        app?.onUsdtAmountChange(
                            NumberPadInputHandler.handleInput(it, uiState.paykitAmount, maxLength = 21, maxDecimals = 6)
                        )
                    },
                    onDeleteLongPress = { app?.onUsdtAmountChange("") }
                )
            } else {
                NumberPad(
                    enabled = uiState.isAmountEditable,
                    viewModel = amountInputViewModel,
                    currencies = currencies,
                    availableHeight = maxHeight,
                    modifier = Modifier
                        .testTag("SendAmountNumberPad")
                )
            }

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                enabled = uiState.isAmountInputValid && !uiState.isFundingSourceLoading,
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
    onClick: (SendFundingSource) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val isHardware = uiState.hardwareWalletId != null
    val testId = when {
        uiState.canSwitchFundingSource -> "switch"
        uiState.paykitUsesUsdt -> "usdt"
        isHardware -> "trezor"
        uiState.payMethod == SendMethod.ONCHAIN -> "savings"
        else -> "spending"
    }
    Box {
        NumberPadActionButton(
            text = when {
                uiState.paykitUsesUsdt -> "USDT"
                isHardware -> uiState.hardwareWalletName ?: stringResource(R.string.hardware__device_model_trezor)
                uiState.payMethod == SendMethod.ONCHAIN -> stringResource(R.string.wallet__savings__title)
                else -> stringResource(R.string.wallet__spending__title)
            },
            color = paymentMethodColor(uiState),
            icon = if (uiState.canSwitchFundingSource) R.drawable.ic_transfer else null,
            onClick = { expanded = true },
            enabled = uiState.fundingOptions.isNotEmpty(),
            isLoading = uiState.isFundingSourceLoading,
            clickable = !uiState.isLoading,
            modifier = Modifier
                .height(28.dp)
                .testTag("AssetButton-$testId")
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Colors.Gray6,
            modifier = Modifier.width(250.dp).testTag("SendFundingSources")
        ) {
            VerticalSpacer(4.dp)
            uiState.fundingOptions.forEachIndexed { index, option ->
                FundingSourceOption(option, uiState, onClick = {
                    expanded = false
                    onClick(option.source)
                })
                if (index < uiState.fundingOptions.lastIndex) {
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Colors.White10)
                }
            }
            VerticalSpacer(4.dp)
        }
    }
}

@Composable
private fun FundingSourceOption(option: SendFundingOption, state: SendUiState, onClick: () -> Unit) {
    val source = option.source
    val title = when (source) {
        SendFundingSource.Savings -> stringResource(R.string.wallet__savings__title)
        SendFundingSource.Spending -> stringResource(R.string.wallet__spending__title)
        SendFundingSource.Usdt -> "USDT"
        is SendFundingSource.Hardware -> option.name ?: stringResource(R.string.hardware__device_model_trezor)
    }
    val color = when (source) {
        SendFundingSource.Savings -> Colors.Brand
        SendFundingSource.Spending -> Colors.Purple
        SendFundingSource.Usdt -> Colors.Usdt
        is SendFundingSource.Hardware -> Colors.Blue
    }
    Row(
        Modifier.fillMaxWidth().alpha(if (option.enabled) 1f else DISABLED_FUNDING_SOURCE_ALPHA)
            .clickable(enabled = option.enabled && !state.isFundingSourceLoading, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        WalletBalanceContent(
            title = title,
            icon = { FundingSourceIcon(source) },
            titleTrailing = {
                option.transportType?.let { transport ->
                    HorizontalSpacer(8.dp)
                    HwWalletConnectionIcon(transport, option.isConnected, modifier = Modifier.size(16.dp))
                }
            },
        ) {
            FundingSourceBalance(option)
            FillWidth()
            if (state.isSelected(source)) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun FundingSourceIcon(source: SendFundingSource) {
    Image(
        painterResource(
            when (source) {
                SendFundingSource.Savings -> R.drawable.ic_btc_circle
                SendFundingSource.Spending -> R.drawable.ic_ln_circle
                SendFundingSource.Usdt -> R.drawable.tether_circle
                is SendFundingSource.Hardware -> R.drawable.ic_btc_circle_blue
            }
        ),
        contentDescription = null,
        modifier = Modifier.padding(end = 4.dp).size(24.dp)
    )
}

@Composable
private fun FundingSourceBalance(option: SendFundingOption) {
    val balances = LocalBalances.current
    val usdt = appViewModel?.usdtWallet?.collectAsStateWithLifecycle()?.value
    val hidden = settingsViewModel?.hideBalance?.collectAsStateWithLifecycle()?.value == true
    val source = option.source
    if (source == SendFundingSource.Usdt) {
        BodyMSB(
            if (hidden) UiConstants.HIDE_BALANCE_SHORT else usdt?.balance?.let { usdtOverviewAmount(it) } ?: "—"
        )
    } else {
        MoneyMSB(
            sats = when (source) {
                SendFundingSource.Savings -> balances.maxSendOnchainSats.toLong()
                SendFundingSource.Spending -> balances.maxSendLightningSats.toLong()
                else -> option.balance.toLong()
            },
            unit = PrimaryDisplay.BITCOIN,
            showSymbol = false,
        )
    }
}

private fun SendUiState.isSelected(source: SendFundingSource): Boolean = when (source) {
    SendFundingSource.Usdt -> paykitUsesUsdt
    SendFundingSource.Spending ->
        !paykitUsesUsdt && hardwareWalletId == null && payMethod == SendMethod.LIGHTNING
    SendFundingSource.Savings ->
        !paykitUsesUsdt && hardwareWalletId == null && payMethod == SendMethod.ONCHAIN
    is SendFundingSource.Hardware -> hardwareWalletId == source.walletId
}

private fun paymentMethodColor(uiState: SendUiState): Color = when {
    uiState.paykitUsesUsdt -> Colors.Usdt
    uiState.hardwareWalletId != null -> Colors.Blue
    uiState.payMethod == SendMethod.ONCHAIN -> Colors.Brand
    else -> Colors.Purple
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLightningNoAmount() {
    AppThemeSurface {
        BottomSheetPreview {
            SendAmountContent(
                nodeLifecycleState = NodeLifecycleState.Running,
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
                nodeLifecycleState = NodeLifecycleState.Running,
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
                nodeLifecycleState = NodeLifecycleState.Running,
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
                nodeLifecycleState = NodeLifecycleState.Initializing,
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
                nodeLifecycleState = NodeLifecycleState.Running,
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
                nodeLifecycleState = NodeLifecycleState.Running,
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
                nodeLifecycleState = NodeLifecycleState.Running,
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
