package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.UsdtBridgeProvider
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.getClipboardText
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadAmountText
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PaymentAddressInput
import to.bitkit.ui.components.PaymentRequestInvoiceNote
import to.bitkit.ui.components.PaymentRequestSummary
import to.bitkit.ui.components.PaymentReviewIllustration
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SendCell
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.wallets.send.HourglassAnimation
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.sheets.NewTransactionSheetView
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadInputHandler

@Suppress("CyclomaticComplexMethod")
@Composable
internal fun ColumnScope.UsdtPaymentContent(
    wallet: UsdtWalletState,
    state: UsdtSendState,
    page: UsdtPage,
    recipient: String,
    amount: String,
    destination: UsdtDestination,
    onRecipientChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onRecipientContinue: (String) -> Unit,
    onPageChange: (UsdtPage) -> Unit,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onConfirm: () -> Unit,
    onDone: () -> Unit,
    onDetails: () -> Unit = {},
    hideBalance: Boolean = false,
    amountEditable: Boolean = true,
    isPaymentRequest: Boolean = false,
    paymentRequestNote: String? = null,
    contact: PubkyProfile? = null,
    onDestinationChange: ((UsdtDestination) -> Unit)? = null,
    destinations: ImmutableList<UsdtDestination> = Env.usdtDestinations.toImmutableList(),
) {
    val showSubmitted = state.submitted && !state.busy
    val quote = state.quote
    val status = wallet.transfers.firstOrNull { it.id == quote?.id }?.status
    if (showSubmitted && quote != null && status == UsdtTransferStatus.CONFIRMED) {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.ONCHAIN,
                direction = NewTransactionSheetDirection.SENT,
                activityId = quote.id,
                usdtAmount = quote.amount,
            ),
            onCloseClick = onDone,
            onDetailClick = onDetails,
            hideBalance = hideBalance,
            modifier = Modifier.fillMaxSize().testTag("UsdtSendSuccess")
        )
        return
    }
    val bridgeNeedsAttention = status == UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION ||
        status == UsdtTransferStatus.BRIDGE_FAILED
    val failed = status == UsdtTransferStatus.FAILED || status == UsdtTransferStatus.REPLACED
    val title = when {
        showSubmitted && status == UsdtTransferStatus.BRIDGE_REFUNDED -> R.string.usdt__deposit_refunded
        showSubmitted && bridgeNeedsAttention -> R.string.usdt__bridge_attention
        showSubmitted && failed -> R.string.wallet__send_error_tx_failed
        showSubmitted -> R.string.usdt__submitted
        quote != null && isPaymentRequest -> R.string.wallet__payment_request
        quote != null -> R.string.wallet__send_review
        page == UsdtPage.AMOUNT -> R.string.usdt__amount
        else -> R.string.usdt__send_title
    }
    SheetTopBar(stringResource(title), onBack = onBack.takeUnless { showSubmitted || state.busy }, action = {
        if (contact != null && !showSubmitted) { PubkyContactAvatar(profile = contact, size = 32.dp) }
    })
    VerticalSpacer(16.dp)
    when {
        showSubmitted -> UsdtSubmittedContent(
            quote,
            failed,
            bridgeNeedsAttention,
            status == UsdtTransferStatus.BRIDGE_REFUNDED,
            onDetails,
            onDone
        )
        quote != null -> UsdtConfirmation(
            quote,
            state,
            isPaymentRequest,
            paymentRequestNote,
            contact,
            onConfirm,
            onEditAmount = { onPageChange(UsdtPage.AMOUNT) },
        )
        page == UsdtPage.AMOUNT -> UsdtAmountContent(
            amount,
            destination,
            wallet,
            state,
            onAmountChange,
            onReview,
            hideBalance,
            amountEditable
        )
        page == UsdtPage.MANUAL -> UsdtManualContent(
            recipient,
            destination,
            onRecipientChange,
            onRecipientContinue,
            state.error,
            onDestinationChange,
            destinations
        )
        else -> UsdtRecipientContent(
            destination,
            onRecipientContinue,
            onPageChange,
            state.error,
            onDestinationChange,
            destinations
        )
    }
}

@Composable
private fun ColumnScope.UsdtSubmittedContent(
    quote: UsdtQuote?,
    failed: Boolean,
    needsAttention: Boolean,
    refunded: Boolean,
    onDetails: () -> Unit,
    onDone: () -> Unit,
) {
    quote?.let { UsdtAmountHeader(usdtFormatAmount(it.amount)) }
    VerticalSpacer(32.dp)
    BodyM(
        stringResource(
            when {
                refunded -> R.string.usdt__bridge_refunded_description
                needsAttention -> R.string.usdt__bridge_attention
                failed -> R.string.usdt__send_failed_description
                quote?.destination == UsdtDestination.ARBITRUM -> R.string.usdt__submitted_description
                else -> R.string.usdt__bridge_submitted_description
            }
        ),
        color = Colors.White64
    )
    FillHeight()
    if (!failed && !needsAttention && !refunded) {
        HourglassAnimation(
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
    FillHeight()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton(
            text = stringResource(R.string.wallet__send_details),
            onClick = onDetails,
            modifier = Modifier.weight(1f)
        )
        PrimaryButton(
            text = stringResource(R.string.common__close),
            onClick = onDone,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun UsdtNetwork(
    destination: UsdtDestination,
    onChange: ((UsdtDestination) -> Unit)?,
    destinations: ImmutableList<UsdtDestination>
) {
    SendCell(caption = stringResource(R.string.usdt__destination)) {
        if (onChange == null || destinations.size == 1) {
            BodySSB(destination.label, modifier = Modifier.height(28.dp).testTag("UsdtNetwork"))
        } else {
            var expanded by remember { mutableStateOf(false) }
            Box {
                NumberPadActionButton(
                    destination.label,
                    onClick = { expanded = true },
                    color = Colors.Usdt,
                    modifier = Modifier.testTag("UsdtNetwork")
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    containerColor = Colors.Gray6,
                ) {
                    destinations.forEach { network ->
                        DropdownMenuItem(text = { BodySSB(network.label) }, onClick = {
                            expanded = false
                            onChange(network)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun UsdtRecipientContent(
    destination: UsdtDestination,
    onRecipientContinue: (String) -> Unit,
    onPageChange: (UsdtPage) -> Unit,
    error: Int?,
    onDestinationChange: ((UsdtDestination) -> Unit)?,
    destinations: ImmutableList<UsdtDestination>,
) {
    val context = LocalContext.current
    var emptyClipboard by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        UsdtNetwork(destination, onDestinationChange, destinations)
        if (!LocalInspectionMode.current) {
            QrScanningScreen(
                onScanSuccess = { it.text?.trim()?.let(onRecipientContinue) },
                modifier = Modifier.weight(1f)
            )
        } else { FillHeight() }
        RectangleButton(
            label = stringResource(R.string.wallet__payment_request_paste),
            icon = R.drawable.ic_clipboard_text,
            iconTint = Colors.Usdt,
            modifier = Modifier.testTag("UsdtPaste")
        ) {
            val value = context.getClipboardText()?.trim()
            if (value.isNullOrBlank()) {
                emptyClipboard = true
            } else {
                emptyClipboard = false
                onRecipientContinue(value)
            }
        }
        RectangleButton(
            label = stringResource(R.string.wallet__recipient_manual),
            icon = R.drawable.ic_pencil_simple,
            iconTint = Colors.Usdt,
            modifier = Modifier.testTag("UsdtManual")
        ) { onPageChange(UsdtPage.MANUAL) }
        error?.let {
            BodyS(
                stringResource(it),
                color = Colors.Brand,
                modifier = Modifier.testTag("UsdtRecipientError")
            )
        }
        if (emptyClipboard) BodyS(stringResource(R.string.wallet__send_clipboard_empty_text), color = Colors.Brand)
        BodyS(stringResource(R.string.usdt__network_warning), color = Colors.White64)
    }
}

@Composable
private fun UsdtManualContent(
    recipient: String,
    destination: UsdtDestination,
    onRecipientChange: (String) -> Unit,
    onRecipientContinue: (String) -> Unit,
    error: Int?,
    onDestinationChange: ((UsdtDestination) -> Unit)?,
    destinations: ImmutableList<UsdtDestination>,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier = Modifier.fillMaxSize()) {
        UsdtNetwork(destination, onDestinationChange, destinations)
        VerticalSpacer(16.dp)
        Caption13Up(stringResource(R.string.wallet__send_to), color = Colors.White64)
        VerticalSpacer(8.dp)
        PaymentAddressInput(
            value = recipient,
            onValueChange = onRecipientChange,
            placeholder = stringResource(R.string.usdt__address),
            modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus).testTag("UsdtRecipient")
        )
        VerticalSpacer(16.dp)
        error?.let {
            BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("UsdtRecipientError"))
            VerticalSpacer(16.dp)
        }
        PrimaryButton(
            text = stringResource(R.string.common__continue),
            enabled = recipient.isNotBlank(),
            onClick = { onRecipientContinue(recipient) },
            modifier = Modifier.testTag("UsdtRecipientContinue")
        )
    }
}

@Composable
private fun UsdtAmountContent(
    amount: String,
    destination: UsdtDestination,
    wallet: UsdtWalletState,
    state: UsdtSendState,
    onAmountChange: (String) -> Unit,
    onReview: () -> Unit,
    hideBalance: Boolean,
    amountEditable: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Caption13Up("USDT · " + destination.label, color = Colors.White64)
        VerticalSpacer(8.dp)
        NumberPadAmountText(value = amount.ifEmpty { "0" }, symbol = "$", modifier = Modifier.testTag("UsdtAmount"))
        VerticalSpacer(32.dp)
        state.error?.let {
            BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("UsdtError"))
            VerticalSpacer(16.dp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Caption13Up(stringResource(R.string.usdt__balance), color = Colors.White64)
                VerticalSpacer(8.dp)
                BodySSB(
                    (
                        if (hideBalance) {
                            UiConstants.HIDE_BALANCE_SHORT
                        } else {
                            wallet.balance?.let {
                                usdtFormatAmount(it)
                            } ?: "—"
                        }
                        ) + " USDT"
                )
            }
            NumberPadActionButton(text = "USDT", color = Colors.Usdt, enabled = false, onClick = {})
        }
        VerticalSpacer(16.dp)
        HorizontalDivider()
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
            NumberPad(
                type = NumberPadType.DECIMAL,
                availableHeight = maxHeight,
                enabled = !state.busy && amountEditable,
                onDeleteLongPress = { onAmountChange("") },
                onPress = { key ->
                    onAmountChange(NumberPadInputHandler.handleInput(key, amount, maxLength = 21, maxDecimals = 6))
                },
            )
        }
        VerticalSpacer(16.dp)
        PrimaryButton(
            text = stringResource(R.string.common__continue),
            onClick = onReview,
            enabled = amount.isNotEmpty(),
            isLoading = state.busy,
            modifier = Modifier.testTag("UsdtReview")
        )
    }
}

@Composable
private fun UsdtConfirmation(
    quote: UsdtQuote,
    state: UsdtSendState,
    isPaymentRequest: Boolean,
    paymentRequestNote: String?,
    contact: PubkyProfile?,
    onConfirm: () -> Unit,
    onEditAmount: () -> Unit,
) {
    var showDetails by rememberSaveable(quote.id) { mutableStateOf(false) }
    val swipeProgress = remember { mutableFloatStateOf(0f) }
    val note = paymentRequestNote?.trim()?.takeIf { it.isNotEmpty() }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            UsdtAmountHeader(
                usdtFormatAmount(quote.amount),
                onClick = { if (!state.busy) onEditAmount() }
            )
            VerticalSpacer(if (!isPaymentRequest) 44.dp else 24.dp)
            if (showDetails) {
                UsdtQuoteDetails(quote, contact)
                note?.let {
                    VerticalSpacer(16.dp)
                    PaymentRequestInvoiceNote(it)
                }
            } else if (quote.destination == UsdtDestination.ARBITRUM) {
                if (isPaymentRequest) {
                    PaymentRequestSummary(profile = contact, note = note)
                    VerticalSpacer(16.dp)
                }
                PaymentReviewIllustration(
                    swipeProgress = { swipeProgress.floatValue },
                    modifier = Modifier.heightIn(max = 220.dp)
                )
            }
            VerticalSpacer(16.dp)
            UsdtQuoteCosts(quote)
            state.error?.let {
                BodyS(
                    stringResource(it),
                    color = Colors.Brand,
                    modifier = Modifier.testTag("UsdtError")
                )
            }
        }
        PrimaryButton(
            text = stringResource(if (showDetails) R.string.common__hide_details else R.string.common__show_details),
            size = ButtonSize.Small,
            onClick = { showDetails = !showDetails },
            fullWidth = false,
            color = Colors.Gray65,
            enableGradient = false,
            icon = {
                Icon(
                    painterResource(if (showDetails) R.drawable.ic_eye_slash else R.drawable.ic_coins),
                    null,
                    tint = Colors.Usdt,
                    modifier = Modifier.size(16.dp)
                )
            },
            modifier = Modifier.align(
                Alignment.CenterHorizontally
            ).padding(vertical = 24.dp).testTag("UsdtReviewDetails")
        )
        SwipeToConfirm(
            text = stringResource(R.string.wallet__send_swipe),
            color = Colors.Usdt,
            onConfirm = onConfirm,
            enabled = !state.busy,
            loading = state.busy,
            confirmed = state.busy || state.authenticationRequired,
            progress = swipeProgress,
            modifier = Modifier.fillMaxWidth().testTag("UsdtConfirm")
        )
    }
}

@Composable
private fun UsdtQuoteCosts(quote: UsdtQuote) {
    if (quote.destination != UsdtDestination.ARBITRUM) {
        SendCell(
            caption = stringResource(
                if (quote.bridgeProvider == UsdtBridgeProvider.ORCHESTRA) {
                    R.string.usdt__expected_amount
                } else {
                    R.string.usdt__recipient_gets
                }
            )
        ) {
            BodySSB(usdtFormatAmount(quote.receivedAmount) + " USDT")
        }
        VerticalSpacer(16.dp)
        if (quote.bridgeProvider == UsdtBridgeProvider.ORCHESTRA) {
            SendCell(caption = stringResource(R.string.usdt__bridge_deducted_fee)) {
                BodySSB(usdtFormatAmount(quote.amount - minOf(quote.amount, quote.receivedAmount)) + " USDT")
            }
            VerticalSpacer(16.dp)
        }
    }
    SendCell(
        caption = stringResource(R.string.usdt__maximum_fee)
    ) { BodySSB(usdtFormatAmount(quote.maximumFee) + " USDT") }
    VerticalSpacer(12.dp)
    if (quote.destination != UsdtDestination.ARBITRUM) {
        SendCell(
            caption = stringResource(R.string.usdt__maximum_total)
        ) { BodySSB(usdtFormatAmount(quote.amount + quote.maximumFee) + " USDT") }
        VerticalSpacer(12.dp)
    }
    BodyS(
        stringResource(
            if (quote.bridgeProvider == UsdtBridgeProvider.ORCHESTRA) {
                R.string.usdt__bridge_estimate_note
            } else {
                R.string.usdt__fee_note
            }
        ),
        color = Colors.White64
    )
}

@Composable
private fun UsdtQuoteDetails(quote: UsdtQuote, contact: PubkyProfile?) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SendCell(caption = stringResource(R.string.wallet__send_from), modifier = Modifier.weight(1f)) {
            NumberPadActionButton(text = "USDT", color = Colors.Usdt, enabled = false, onClick = {})
        }
        SendCell(
            caption = stringResource(
                if (contact == null) R.string.usdt__destination else R.string.wallet__payment_request_contact
            ),
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
                if (contact != null) {
                    PubkyContactAvatar(profile = contact, size = 20.dp)
                    HorizontalSpacer(4.dp)
                }
                BodySSB(contact?.name ?: quote.destination.label, maxLines = 1)
            }
        }
    }
    VerticalSpacer(16.dp)
    SendCell(
        caption = stringResource(R.string.wallet__send_to)
    ) { SelectionContainer { BodySSB(quote.recipient) } }
    quote.bridgeProvider?.let { provider ->
        VerticalSpacer(16.dp)
        SendCell(caption = stringResource(R.string.usdt__bridge_provider)) {
            BodySSB(if (provider == UsdtBridgeProvider.ORCHESTRA) "Orchestra" else "USDT0")
        }
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        BottomSheetPreview {
            Column(modifier = Modifier.sheetHeight().gradientBackground().padding(horizontal = 16.dp)) {
                UsdtPaymentContent(
                    UsdtWalletState(), UsdtSendState(), UsdtPage.AMOUNT, "", "125", UsdtDestination.ARBITRUM,
                    {}, {}, {}, {}, {}, {}, {}, {}
                )
            }
        }
    }
}
