package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synonym.bitkitcore.UsdtBridgeProvider
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
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
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
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
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.wallets.send.HourglassAnimation
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.sheets.NewTransactionSheetView
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadInputHandler
import kotlin.time.Duration.Companion.seconds

@Suppress("CyclomaticComplexMethod")
@Composable
internal fun UsdtPaymentContent(
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
    onMaximum: (() -> Unit)? = null,
    hideBalance: Boolean = false,
    amountEditable: Boolean = true,
    isPaymentRequest: Boolean = false,
    paymentRequestNote: String? = null,
    contact: PubkyProfile? = null,
    onDestinationChange: ((UsdtDestination) -> Unit)? = null,
    destinations: ImmutableList<UsdtDestination> = Env.usdtDestinations.toImmutableList(),
) {
    if (page == UsdtPage.WALLET) return

    var showNetworks by rememberSaveable { mutableStateOf(false) }
    if (showNetworks) {
        BackHandler { showNetworks = false }
        UsdtNetworkPicker(
            destination,
            destinations,
            onSelect = { network ->
                showNetworks = false
                onDestinationChange?.invoke(network)
            },
            onBack = { showNetworks = false },
        )
        return
    }
    val onNetworkClick: (() -> Unit)? = onDestinationChange?.let { { showNetworks = true } }
    val quote = state.quote
    val status = wallet.transfers.firstOrNull { it.id == quote?.id }?.status
    val completed = state.submitted && !state.busy && status == UsdtTransferStatus.CONFIRMED
    val pending = state.submitted && !state.busy && !completed
    var hasShownSubmitted by rememberSaveable(quote?.id) { mutableStateOf(state.submitted && !state.busy) }
    var showSuccess by rememberSaveable(quote?.id) { mutableStateOf(false) }
    LaunchedEffect(quote?.id, pending) {
        if (pending) hasShownSubmitted = true
    }
    LaunchedEffect(quote?.id, completed) {
        if (completed) {
            delay(1.seconds)
            showSuccess = true
        }
    }
    val showSubmitted = pending || (completed && hasShownSubmitted)
    if (showSuccess && completed && quote != null) {
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
        showSubmitted && state.error != null -> R.string.wallet__payment_request_expired
        showSubmitted -> R.string.usdt__submitted
        quote != null && isPaymentRequest -> R.string.wallet__payment_request
        quote != null -> R.string.wallet__send_review
        page == UsdtPage.AMOUNT -> R.string.usdt__amount
        else -> R.string.usdt__send_title
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SheetTopBar(stringResource(title), onBack = onBack.takeUnless { state.submitted || state.busy }, action = {
            if (contact != null && !showSubmitted) { PubkyContactAvatar(profile = contact, size = 32.dp) }
        })
        VerticalSpacer(if (quote != null || page == UsdtPage.AMOUNT) 8.dp else 16.dp)
        when {
            showSubmitted -> UsdtSubmittedContent(
                quote,
                failed,
                bridgeNeedsAttention,
                status == UsdtTransferStatus.BRIDGE_REFUNDED,
                state.error,
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
                completed,
                onEditAmount = { onPageChange(UsdtPage.AMOUNT) },
            )
            page == UsdtPage.AMOUNT -> UsdtAmountContent(
                amount,
                destination,
                wallet,
                state,
                onAmountChange,
                onReview,
                onMaximum,
                hideBalance,
                amountEditable,
                onNetworkClick,
                destinations
            )
            page == UsdtPage.MANUAL -> UsdtManualContent(
                recipient,
                destination,
                onRecipientChange,
                onRecipientContinue,
                state.error,
                onNetworkClick,
                destinations
            )
            else -> UsdtRecipientContent(
                destination,
                onRecipientContinue,
                onPageChange,
                state.error,
                onNetworkClick,
                destinations
            )
        }
    }
}

@Composable
private fun ColumnScope.UsdtSubmittedContent(
    quote: UsdtQuote?,
    failed: Boolean,
    needsAttention: Boolean,
    refunded: Boolean,
    error: Int?,
    onDetails: () -> Unit,
    onDone: () -> Unit,
) {
    quote?.let { UsdtAmountHeader(usdtFormatAmount(it.amount), displayAmount = usdtOverviewAmount(it.amount)) }
    VerticalSpacer(32.dp)
    BodyM(
        stringResource(
            error ?: when {
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
    onChange: (() -> Unit)?,
    destinations: ImmutableList<UsdtDestination>
) {
    SendCell(caption = stringResource(R.string.usdt__network)) {
        UsdtNetworkButton(destination, onChange, destinations)
    }
}

@Composable
internal fun UsdtNetworkButton(
    destination: UsdtDestination,
    onChange: (() -> Unit)?,
    destinations: ImmutableList<UsdtDestination>
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(28.dp).background(Colors.White.copy(alpha = 0.01f), RoundedCornerShape(12.dp))
            .border(1.dp, Colors.White16, RoundedCornerShape(12.dp))
            .clickable(enabled = onChange != null && destinations.size > 1) { onChange?.invoke() }
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("UsdtNetwork")
    ) {
        Icon(painterResource(R.drawable.usdt_network), null, tint = Colors.Usdt, modifier = Modifier.size(16.dp))
        CaptionB(destination.label, color = Colors.White64, maxLines = 1, letterSpacing = 0.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun UsdtRecipientContent(
    destination: UsdtDestination,
    onRecipientContinue: (String) -> Unit,
    onPageChange: (UsdtPage) -> Unit,
    error: Int?,
    onNetworkClick: (() -> Unit)?,
    destinations: ImmutableList<UsdtDestination>,
) {
    val context = LocalContext.current
    var emptyClipboard by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        UsdtNetwork(destination, onNetworkClick, destinations)
        if (!LocalInspectionMode.current) {
            QrScanningScreen(
                showsGradientBackground = false,
                permissionDescription = stringResource(R.string.usdt__camera_permission_description),
                permissionAccentColor = Colors.Usdt,
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
    onNetworkClick: (() -> Unit)?,
    destinations: ImmutableList<UsdtDestination>,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier = Modifier.fillMaxSize()) {
        UsdtNetwork(destination, onNetworkClick, destinations)
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
    onMaximum: (() -> Unit)?,
    hideBalance: Boolean,
    amountEditable: Boolean,
    onNetworkClick: (() -> Unit)?,
    destinations: ImmutableList<UsdtDestination>,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        UsdtAmountHeader(amount.ifEmpty { "0" }, modifier = Modifier.testTag("UsdtAmount"))
        VerticalSpacer(32.dp)
        state.error?.let {
            BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("UsdtError"))
            VerticalSpacer(16.dp)
        }
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically)) {
                Caption13Up(
                    stringResource(R.string.wallet__send_available),
                    color = Colors.White64,
                    style = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp),
                )
                VerticalSpacer(4.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!hideBalance && wallet.balance != null) BodySSB("$", color = Colors.White64)
                    BodySSB(
                        text = if (hideBalance) {
                            UiConstants.HIDE_BALANCE_SHORT
                        } else {
                            wallet.balance?.let { usdtOverviewAmount(it) } ?: "—"
                        },
                        style = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                NumberPadActionButton(
                    text = "USDT",
                    color = Colors.Usdt,
                    enabled = false,
                    textStyle = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp),
                    onClick = {},
                )
                if (onMaximum != null && amountEditable) {
                    NumberPadActionButton(
                        text = stringResource(R.string.common__max),
                        color = Colors.Usdt,
                        onClick = onMaximum,
                        enabled = !state.busy,
                        isLoading = state.preparingMaximum,
                        textStyle = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp),
                        modifier = Modifier.testTag("UsdtMax")
                    )
                }
                if (onNetworkClick != null) {
                    UsdtNetworkButton(
                        destination,
                        onNetworkClick.takeUnless { state.busy },
                        destinations,
                    )
                }
            }
        }
        VerticalSpacer(16.dp)
        HorizontalDivider(color = Colors.White10)
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
            enabled = amount.isNotEmpty() && !state.busy,
            isLoading = state.busy && !state.preparingMaximum,
            textStyle = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp),
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
    completed: Boolean,
    onEditAmount: () -> Unit,
) {
    var showDetails by rememberSaveable(quote.id) { mutableStateOf(false) }
    val swipeProgress = remember { mutableFloatStateOf(0f) }
    val note = paymentRequestNote?.trim()?.takeIf { it.isNotEmpty() }
    val scrollState = rememberScrollState()
    LaunchedEffect(showDetails) { scrollState.scrollTo(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            Column(
                verticalArrangement = if (showDetails) Arrangement.Top else Arrangement.SpaceBetween,
                modifier = Modifier.verticalScroll(scrollState).heightIn(min = maxHeight)
            ) {
                UsdtReviewHeader(
                    quote,
                    contact,
                    note,
                    isPaymentRequest,
                    showDetails,
                ) {
                    if (!state.busy && !completed) onEditAmount()
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!showDetails) {
                        PaymentReviewIllustration(
                            swipeProgress = { swipeProgress.floatValue },
                            isUsdt = true,
                            modifier = Modifier.height(256.dp)
                        )
                    }
                    state.error?.let {
                        BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("UsdtError"))
                    }
                    UsdtReviewDetailsButton(showDetails) { showDetails = !showDetails }
                }
            }
        }
        if (showDetails) UsdtFeeNote()
        SwipeToConfirm(
            text = stringResource(R.string.wallet__send_swipe),
            color = Colors.Usdt,
            onConfirm = onConfirm,
            enabled = !state.busy && !completed,
            loading = state.busy && !completed,
            confirmed = state.busy || state.authenticationRequired || completed,
            progress = swipeProgress,
            textStyle = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp),
            modifier = Modifier.fillMaxWidth().testTag("UsdtConfirm")
        )
    }
}

@Composable
private fun ColumnScope.UsdtReviewDetailsButton(showDetails: Boolean, onClick: () -> Unit) {
    PrimaryButton(
        text = stringResource(
            if (showDetails) R.string.common__hide_details else R.string.common__show_details
        ),
        size = ButtonSize.Small,
        onClick = onClick,
        textStyle = UsdtDetailStyles.SmallButton,
        fullWidth = false,
        color = Colors.White06,
        enableGradient = false,
        icon = {
            Icon(
                painterResource(
                    if (showDetails) R.drawable.ic_eye_slash else R.drawable.ic_speed_normal
                ),
                null,
                tint = Colors.Usdt,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.align(
            Alignment.CenterHorizontally
        ).padding(vertical = 24.dp).testTag("UsdtReviewDetails")
    )
}

@Composable
private fun UsdtFeeNote() {
    BodyS(
        stringResource(R.string.usdt__fee_note),
        color = Colors.White64,
        textAlign = TextAlign.Center,
        letterSpacing = 0.sp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    )
}

@Composable
private fun UsdtReviewHeader(
    quote: UsdtQuote,
    contact: PubkyProfile?,
    note: String?,
    isPaymentRequest: Boolean,
    showDetails: Boolean,
    modifier: Modifier = Modifier,
    onEditAmount: () -> Unit,
) {
    Column(modifier = modifier) {
        UsdtAmountHeader(
            usdtFormatAmount(quote.amount),
            displayAmount = usdtOverviewAmount(quote.amount),
            onClick = onEditAmount
        )
        VerticalSpacer(24.dp)
        if (showDetails) {
            UsdtQuoteDetails(quote, contact)
            note?.let {
                VerticalSpacer(16.dp)
                PaymentRequestInvoiceNote(it)
            }
        } else if (isPaymentRequest) {
            PaymentRequestSummary(profile = contact, note = note, accentColor = Colors.Usdt)
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun UsdtQuoteDetails(quote: UsdtQuote, contact: PubkyProfile?) {
    var showFullAddress by rememberSaveable(quote.recipient) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailField(caption = stringResource(R.string.usdt__pay_from), modifier = Modifier.weight(1f)) {
            NumberPadActionButton(
                text = "USDT",
                color = Colors.Usdt,
                enabled = false,
                textStyle = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp),
                onClick = {},
            )
        }
        UsdtDetailCell(
            stringResource(R.string.usdt__network),
            UsdtDestination.ARBITRUM.label,
            R.drawable.usdt_network,
            minValueHeight = 28.dp,
            modifier = Modifier.weight(1f)
        )
    }
    VerticalSpacer(16.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_payment),
            usdtOverviewAmount(quote.amount),
            R.drawable.ic_user,
            modifier = Modifier.weight(1f)
        )
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_fee),
            usdtOverviewAmount(quote.maximumFee),
            R.drawable.ic_speed_normal,
            modifier = Modifier.weight(1f)
        )
    }
    VerticalSpacer(16.dp)
    if (quote.destination != UsdtDestination.ARBITRUM) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val estimate = if (quote.bridgeProvider == UsdtBridgeProvider.ORCHESTRA) "±" else ""
            UsdtDetailCell(
                stringResource(R.string.usdt__recipient_gets),
                estimate + usdtOverviewAmount(quote.receivedAmount),
                R.drawable.ic_arrow_down_right,
                modifier = Modifier.weight(1f).testTag("UsdtRecipientAmount")
            )
            UsdtDetailCell(
                stringResource(R.string.usdt__maximum_total),
                usdtOverviewAmount(quote.amount + quote.maximumFee),
                R.drawable.ic_arrow_up_right,
                modifier = Modifier.weight(1f).testTag("UsdtMaximumTotal")
            )
        }
        VerticalSpacer(16.dp)
    }
    if (contact != null) {
        SendCell(caption = stringResource(R.string.wallet__payment_request_contact)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PubkyContactAvatar(profile = contact, size = 20.dp)
                HorizontalSpacer(4.dp)
                BodySSB(contact.name, maxLines = 1)
            }
        }
        VerticalSpacer(16.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        UsdtDetailField(
            caption = stringResource(R.string.wallet__activity_address),
            alignDividerToBottom = true,
            modifier = Modifier.weight(1f).fillMaxHeight()
        ) {
            SelectionContainer {
                BodySSB(
                    quote.recipient,
                    maxLines = if (showFullAddress) Int.MAX_VALUE else 1,
                    overflow = if (showFullAddress) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
                    style = UsdtDetailStyles.Value.copy(letterSpacing = 0.sp),
                    modifier = Modifier
                        .clickable { showFullAddress = !showFullAddress }
                        .testTag("UsdtReviewAddress")
                )
            }
        }
        if (quote.destination != UsdtDestination.ARBITRUM) {
            UsdtDetailCell(
                stringResource(R.string.usdt__recipient_network),
                quote.destination.label,
                R.drawable.usdt_network,
                alignDividerToBottom = true,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        BottomSheetPreview {
            Column(modifier = Modifier.sheetHeight().background(Colors.Gray7)) {
                UsdtPaymentContent(
                    UsdtWalletState(), UsdtSendState(), UsdtPage.AMOUNT, "", "125", UsdtDestination.ARBITRUM,
                    {}, {}, {}, {}, {}, {}, {}, {}
                )
            }
        }
    }
}
