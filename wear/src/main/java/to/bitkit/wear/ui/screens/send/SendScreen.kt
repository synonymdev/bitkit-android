package to.bitkit.wear.ui.screens.send

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import to.bitkit.wear.R
import to.bitkit.wear.models.formatToModernDisplay
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.HoldToPayRing
import to.bitkit.wear.ui.components.LargeAmountRow
import to.bitkit.wear.ui.components.ScreenAwake
import to.bitkit.wear.ui.components.SmallAmountRow
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import to.bitkit.wear.ui.components.amountRowGap
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.components.rememberHoldToPayState
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.theme.Colors

/** The size of the icon that tops a status: the check, the cross, the clock, the cloud and the bolt while loading. */
private val STATUS_ICON_SIZE = 48.dp

/** The opacity of the bolt that shows while the send is loading or under way. */
private const val LOADING_ICON_ALPHA = 0.4f

/**
 * The send flow of a deeplink: review with the hold to pay, the send under way, and its result, which closes the
 * flow by itself ([onClose]). The amount entry shows only for an input that names no amount. Offline before the
 * send starts, the flow ends on a refusal of its own. While a send is under way the screen stays on and cannot be
 * left ([onBusyChange]). In ambient mode a step with an amount hides behind a
 * dimmed bolt, as no amount shows on the always-on screen.
 */
@Composable
fun SendScreen(
    onClose: () -> Unit,
    onBusyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SendViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnBusyChange by rememberUpdatedState(onBusyChange)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                SendEffect.Close -> currentOnClose()
            }
        }
    }
    LaunchedEffect(uiState.isBusy) { currentOnBusyChange(uiState.isBusy) }
    // A screen that leaves while busy must not leave the swipe to dismiss off for every other screen.
    DisposableEffect(Unit) { onDispose { currentOnBusyChange(false) } }
    val step = uiState.step
    LaunchedEffect(step is SendStep.Done, step is SendStep.Failed, step is SendStep.Offline) {
        when (step) {
            is SendStep.Done if step.result == SendResult.Sent ->
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            is SendStep.Failed, SendStep.Offline -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }
    BackHandler(enabled = uiState.isBusy) {}
    Content(
        uiState = uiState,
        isAmbient = isAmbient(),
        onAmountChange = viewModel::onAmountChange,
        onAmountConfirm = viewModel::onAmountConfirm,
        onHoldComplete = viewModel::onHoldComplete,
        onCloseClick = viewModel::onCloseClick,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: SendUiState,
    isAmbient: Boolean,
    onAmountChange: (String) -> Unit,
    onAmountConfirm: () -> Unit,
    onHoldComplete: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = uiState.step
    // The always-on screen shows no amount, typed, reviewed or sent, and nothing to hold.
    if (isAmbient && (step is SendStep.Amount || step is SendStep.Review || step is SendStep.Done)) {
        Hidden(modifier = modifier)
        return
    }
    when (step) {
        SendStep.Loading -> Loading(modifier = modifier)
        is SendStep.Amount -> Amount(
            step = step,
            text = uiState.amountText,
            onTextChange = onAmountChange,
            onConfirm = onAmountConfirm,
            modifier = modifier
        )
        is SendStep.Review -> Review(
            step = step,
            isHoldEnabled = uiState.isHoldEnabled,
            isConnecting = !uiState.isWalletReady,
            onHoldComplete = onHoldComplete,
            modifier = modifier
        )
        is SendStep.Sending -> Sending(isWaiting = step.isWaiting, modifier = modifier)
        is SendStep.Done -> Done(step = step, modifier = modifier)
        is SendStep.Failed -> Refusal(
            icon = R.drawable.ic_close,
            text = stringResource(step.failure.text),
            textTag = "WatchSendError",
            onCloseClick = onCloseClick,
            modifier = modifier
        )
        SendStep.Offline -> Refusal(
            icon = R.drawable.ic_cloud_off,
            text = stringResource(R.string.send__error_offline),
            textTag = "WatchSendOfflineError",
            onCloseClick = onCloseClick,
            modifier = modifier
        )
    }
}

@Composable
private fun Hidden(modifier: Modifier = Modifier) {
    CenteredScreen(modifier = modifier) {
        StatusIcon(
            icon = R.drawable.ic_bolt,
            tint = Colors.White.copy(alpha = LOADING_ICON_ALPHA),
            modifier = Modifier.testTag("WatchSendHidden")
        )
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    CenteredScreen(
        overlay = { BezelLoading() },
        modifier = modifier
    ) {
        StatusIcon(
            icon = R.drawable.ic_bolt,
            tint = Colors.White.copy(alpha = LOADING_ICON_ALPHA),
            modifier = Modifier.testTag("WatchSendLoading")
        )
    }
}

@Composable
private fun Amount(
    step: SendStep.Amount,
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        edgeButton = {
            EdgeButton(
                onClick = onConfirm,
                buttonSize = EdgeButtonSize.Small,
                modifier = Modifier.testTag("WatchSendAmountConfirm")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_forward),
                    contentDescription = stringResource(R.string.send__amount_confirm),
                )
            }
        },
        modifier = modifier
    ) {
        AmountField(
            value = text,
            onValueChange = onTextChange,
            onDone = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("WatchSendAmountInput")
        )
        val min = step.minSats
        val max = step.maxSats
        when {
            step.isOutOfRange -> Line(
                text = stringResource(R.string.send__error_out_of_range),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("WatchSendAmountError")
            )
            min != null && max != null -> Line(
                text = stringResource(
                    R.string.send__amount_range,
                    min.toLong().formatToModernDisplay(),
                    max.toLong().formatToModernDisplay(),
                ),
                modifier = Modifier.testTag("WatchSendAmountRange")
            )
        }
    }
}

@Composable
private fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { innerTextField ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.send__amount_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                innerTextField()
            }
        },
        modifier = modifier
    )
}

@Composable
internal fun Review(
    step: SendStep.Review,
    isHoldEnabled: Boolean,
    isConnecting: Boolean,
    onHoldComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val holdState = rememberHoldToPayState(enabled = isHoldEnabled, onComplete = onHoldComplete)
    val payLabel = stringResource(R.string.send__pay)
    CenteredScreen(
        edgeButton = {
            EdgeButton(
                // A tap never pays: only the hold that rememberHoldToPayState follows does.
                onClick = {},
                enabled = isHoldEnabled,
                buttonSize = EdgeButtonSize.Medium,
                interactionSource = holdState.interactionSource,
                modifier = Modifier
                    .testTag("WatchSendHold")
                    .semantics {
                        // A screen reader cannot hold, and its double tap is the click that pays nothing, so paying
                        // is a named action its wearer picks from the actions menu, or its long click: a double tap
                        // and hold.
                        if (isHoldEnabled) {
                            val pay = {
                                onHoldComplete()
                                true
                            }
                            customActions = listOf(CustomAccessibilityAction(payLabel, pay))
                            onLongClick(label = payLabel, action = pay)
                        }
                    }
            ) {
                Text(stringResource(R.string.send__hold))
            }
        },
        overlay = { if (isConnecting) BezelLoading() else HoldToPayRing(state = holdState) },
        modifier = modifier
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            LargeAmountRow(
                amount = step.amount,
                maxWidth = maxWidth,
                amountTag = "WatchSendAmount",
                symbolTag = "WatchSendAmountSymbol",
            )
        }
        step.fiat?.let {
            SmallAmountRow(
                amount = it,
                amountTag = "WatchSendFiat",
                modifier = Modifier.padding(top = amountRowGap())
            )
        }
        step.fee?.let {
            Line(
                text = stringResource(R.string.send__fee, "${it.symbol} ${it.text}"),
                // The fee is never cut: in bitcoin with its eight decimals a long translation wraps instead.
                maxLines = 2,
                modifier = Modifier.testTag("WatchSendFee")
            )
        }
        step.payee?.let { Line(text = it, modifier = Modifier.testTag("WatchSendPayee")) }
    }
}

@Composable
private fun Sending(
    isWaiting: Boolean,
    modifier: Modifier = Modifier,
) {
    ScreenAwake()
    CenteredScreen(
        overlay = { BezelLoading() },
        modifier = modifier
    ) {
        StatusIcon(
            icon = R.drawable.ic_bolt,
            tint = Colors.White.copy(alpha = LOADING_ICON_ALPHA),
            modifier = Modifier.testTag("WatchSendSending")
        )
        if (isWaiting) {
            Line(text = stringResource(R.string.send__waiting), modifier = Modifier.testTag("WatchSendWaiting"))
        }
    }
}

@Composable
private fun Done(
    step: SendStep.Done,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        when (step.result) {
            SendResult.Sent -> {
                StatusIcon(icon = R.drawable.ic_check, tint = Colors.Brand)
                Text(
                    text = stringResource(R.string.send__sent),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("WatchSendSent")
                )
                SmallAmountRow(amount = step.amount, amountTag = "WatchSendResultAmount")
            }
            SendResult.Pending -> {
                StatusIcon(icon = R.drawable.ic_schedule, tint = Colors.White64)
                Text(
                    text = stringResource(R.string.send__pending),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("WatchSendPending")
                )
            }
            SendResult.Unknown -> {
                StatusIcon(icon = R.drawable.ic_schedule, tint = Colors.White64)
                Text(
                    text = stringResource(R.string.send__unknown),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("WatchSendUnknown")
                )
            }
        }
    }
}

/** Nothing was sent: [icon] and the one line [text], with the one action that closes the flow. */
@Composable
private fun Refusal(
    @DrawableRes icon: Int,
    text: String,
    textTag: String,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        StatusIcon(icon = icon, tint = MaterialTheme.colorScheme.error)
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(textTag)
        )
        FilledTonalIconButton(
            onClick = onCloseClick,
            shapes = IconButtonDefaults.animatedShapes(),
            modifier = Modifier.testTag("WatchSendClose")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.common__ok),
            )
        }
    }
}

@Composable
private fun StatusIcon(
    @DrawableRes icon: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(STATUS_ICON_SIZE)
    )
}

@Composable
private fun Line(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.White64,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@WearPreviewDevices
@Composable
private fun PreviewReview() {
    WearPreview {
        Content(
            uiState = SendUiState(step = PreviewReviewStep, isWalletReady = true),
            isAmbient = false,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewOffline() {
    WearPreview {
        Content(
            uiState = SendUiState(step = SendStep.Offline),
            isAmbient = false,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewReviewConnecting() {
    WearPreview {
        Content(
            uiState = SendUiState(step = PreviewReviewStep),
            isAmbient = false,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewAmbient() {
    WearPreview {
        Content(
            uiState = SendUiState(step = PreviewReviewStep, isWalletReady = true),
            isAmbient = true,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewAmount() {
    WearPreview {
        Content(
            uiState = SendUiState(step = SendStep.Amount(minSats = 1uL, maxSats = 100_000uL), amountText = "2100"),
            isAmbient = false,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewSending() {
    WearPreview {
        Content(
            uiState = SendUiState(step = SendStep.Sending(isWaiting = true)),
            isAmbient = false,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewSent() {
    WearPreview {
        Content(
            uiState = SendUiState(step = SendStep.Done(SendResult.Sent, PreviewBitcoin)),
            isAmbient = false,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewFailed() {
    WearPreview {
        Content(
            uiState = SendUiState(step = SendStep.Failed(SendFailure.OwnInvoice)),
            isAmbient = false,
            onAmountChange = {},
            onAmountConfirm = {},
            onHoldComplete = {},
            onCloseClick = {},
        )
    }
}

private val PreviewBitcoin = DisplayAmount(symbol = "₿", text = "21 000")

private val PreviewReviewStep = SendStep.Review(
    amount = PreviewBitcoin,
    fiat = DisplayAmount(symbol = "$", text = "20.39"),
    fee = DisplayAmount(symbol = "₿", text = "250"),
    payee = "Coffee",
)
