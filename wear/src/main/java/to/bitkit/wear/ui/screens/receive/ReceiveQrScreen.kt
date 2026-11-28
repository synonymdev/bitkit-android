package to.bitkit.wear.ui.screens.receive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.QrCode
import to.bitkit.wear.ui.components.ScreenAwake
import to.bitkit.wear.ui.components.SmallAmountRow
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.modifiers.clickableAlpha
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.theme.Colors
import kotlin.time.Duration.Companion.minutes

/** The QR's side as a share of the screen: the largest square inside the round screen (1/√2). */
private const val QR_SIZE_FRACTION = 0.707f

/** The size of the check and of the dimmed QR icon. */
private val MARK_SIZE = 56.dp

/** The opacity of the QR icon that stands in for the hidden QR in ambient mode. */
private const val HIDDEN_ALPHA = 0.4f

/** How long the QR keeps the screen on at full brightness without a touch, before it rests. */
internal val QR_AWAKE_LIMIT = 2.minutes

/**
 * The receive QR, as large as the round screen allows, at full brightness and with the screen kept on while it
 * shows. After [QR_AWAKE_LIMIT] without a touch it rests: the QR hides behind a dimmed icon and the screen may turn
 * off again, so a forgotten QR does not drain the battery; a tap, or waking the watch from ambient mode, shows it
 * again, as does a tap anywhere on the resting screen. Ambient mode hides it. A paid invoice turns into a check with
 * the amount and its own haptic; a tap on the check makes a new invoice. In ambient mode the check shows without
 * the amount, as the always-on screen shows no amount.
 */
@Composable
fun ReceiveQrScreen(
    modifier: Modifier = Modifier,
    viewModel: ReceiveViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val isPaid = uiState.paidAmount != null
    LaunchedEffect(isPaid) {
        // A firm click, unlike the confirm pattern a send ends with, so the two never feel alike.
        if (isPaid) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val isAmbient = isAmbient()
    var isResting by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    val isQrShown = uiState.qrContent != null && !isPaid && !isAmbient && !isResting
    LaunchedEffect(isQrShown, touches) {
        if (!isQrShown) return@LaunchedEffect
        delay(QR_AWAKE_LIMIT)
        isResting = true
    }
    // A watch that wakes from ambient mode shows the QR again, and so does the invoice after a paid one.
    LaunchedEffect(isAmbient, isPaid) { if (isAmbient || isPaid) isResting = false }
    ReceiveQrContent(
        uiState = uiState,
        isAmbient = isAmbient,
        isResting = isResting,
        onQrTouch = { touches++ },
        onShowClick = { isResting = false },
        onRefreshClick = viewModel::onRefreshClick,
        modifier = modifier
    )
}

@Composable
internal fun ReceiveQrContent(
    uiState: ReceiveUiState,
    isAmbient: Boolean,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
    isResting: Boolean = false,
    onQrTouch: () -> Unit = {},
    onShowClick: () -> Unit = {},
) {
    val qrContent = uiState.qrContent
    val paidAmount = uiState.paidAmount
    when {
        paidAmount != null -> Paid(
            amount = paidAmount.takeUnless { isAmbient },
            onClick = onRefreshClick,
            modifier = modifier
        )
        qrContent != null && isAmbient -> Hidden(modifier = modifier)
        qrContent != null && isResting -> Hidden(onClick = onShowClick, modifier = modifier)
        qrContent != null -> Qr(content = qrContent, onTouch = onQrTouch, modifier = modifier)
        uiState.isError -> Error(onRetryClick = onRefreshClick, modifier = modifier)
        else -> CenteredScreen(
            overlay = { BezelLoading() },
            modifier = modifier
        ) {
            QrIcon(modifier = Modifier.testTag("WatchReceiveLoading"))
        }
    }
}

@Composable
private fun Qr(
    content: String,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTouch by rememberUpdatedState(onTouch)
    ScreenAwake(isBright = true)
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            // Any touch counts as the wearer still using the QR; it is only watched, never consumed.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    currentOnTouch()
                }
            }
    ) {
        QrCode(
            content = content,
            contentDescription = stringResource(R.string.receive__title),
            modifier = Modifier
                .size(minOf(maxWidth, maxHeight) * QR_SIZE_FRACTION)
                .testTag("WatchReceiveQr")
        )
    }
}

@Composable
private fun Hidden(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val description = stringResource(R.string.receive__hidden)
    val showLabel = stringResource(R.string.receive__show)
    CenteredScreen(
        modifier = modifier
            .clickableAlpha(onClickLabel = showLabel, enabled = onClick != null, onClick = { onClick?.invoke() })
            .semantics { contentDescription = description }
            .testTag("WatchReceiveHidden")
    ) {
        QrIcon()
    }
}

@Composable
private fun QrIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_qr_code),
        contentDescription = null,
        tint = Colors.White.copy(alpha = HIDDEN_ALPHA),
        modifier = modifier.size(MARK_SIZE)
    )
}

@Composable
private fun Paid(
    amount: DisplayAmount?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDimmed = amount == null
    CenteredScreen(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clickableAlpha(onClickLabel = stringResource(R.string.receive__new), onClick = onClick)
                .testTag("WatchReceivePaid")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.receive__paid),
                tint = if (isDimmed) Colors.White.copy(alpha = HIDDEN_ALPHA) else Colors.Brand,
                modifier = Modifier.size(MARK_SIZE)
            )
            amount?.let { SmallAmountRow(amount = it, amountTag = "WatchReceivePaidAmount") }
        }
    }
}

@Composable
private fun Error(
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        Text(
            text = stringResource(R.string.receive__error),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("WatchReceiveError")
        )
        FilledTonalIconButton(
            onClick = onRetryClick,
            shapes = IconButtonDefaults.animatedShapes(),
            modifier = Modifier.testTag("WatchReceiveRetry")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.common__retry),
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        ReceiveQrContent(
            uiState = ReceiveUiState(qrContent = PREVIEW_INVOICE),
            isAmbient = false,
            onRefreshClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewPaid() {
    WearPreview {
        ReceiveQrContent(
            uiState = ReceiveUiState(paidAmount = DisplayAmount(symbol = "₿", text = "21 000")),
            isAmbient = false,
            onRefreshClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewPaidAmbient() {
    WearPreview {
        ReceiveQrContent(
            uiState = ReceiveUiState(paidAmount = DisplayAmount(symbol = "₿", text = "21 000")),
            isAmbient = true,
            onRefreshClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewAmbient() {
    WearPreview {
        ReceiveQrContent(
            uiState = ReceiveUiState(qrContent = PREVIEW_INVOICE),
            isAmbient = true,
            onRefreshClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewResting() {
    WearPreview {
        ReceiveQrContent(
            uiState = ReceiveUiState(qrContent = PREVIEW_INVOICE),
            isAmbient = false,
            onRefreshClick = {},
            isResting = true,
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewError() {
    WearPreview {
        ReceiveQrContent(
            uiState = ReceiveUiState(isError = true),
            isAmbient = false,
            onRefreshClick = {},
        )
    }
}

private const val PREVIEW_INVOICE = "LNBCRT1P5EXAMPLEPP5QQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQYPQDQ0XQRRSS"
